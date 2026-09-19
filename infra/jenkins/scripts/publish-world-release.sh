#!/usr/bin/env bash
set -euo pipefail
set +x

usage() {
  echo 'Usage: publish-world-release.sh --source-commit SHA --image-ref REF --content-id sha256:HEX' >&2
  exit 64
}

source_commit=''
image_ref=''
expected_content_id=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --image-ref) image_ref="${2:-}"; shift 2 ;;
    --content-id) expected_content_id="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
[[ -n "${image_ref}" ]] || usage
[[ "${expected_content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || usage
: "${GITLAB_PACKAGE_TOKEN:?GITLAB_PACKAGE_TOKEN is required}"

docker_bin="${DOCKER_BIN:-docker}"
python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
package_name="${WORLD_PACKAGE_NAME:-festa-world}"
release_id="${source_commit:0:8}"
filename="festa-world-release-${release_id}.tar"
sha_filename="${filename}.sha256"
metadata_filename="festa-world-release-${release_id}.json"
package_base="${gitlab_api%/}/projects/${project_id}/packages/generic/${package_name}/${release_id}"
package_url="${package_base}/${filename}"
sha_url="${package_base}/${sha_filename}"
metadata_url="${package_base}/${metadata_filename}"

actual_content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}" 2>/dev/null || true)"
[[ "${actual_content_id}" == "${expected_content_id}" ]] || {
  echo "world source image content ID mismatch: expected=${expected_content_id} actual=${actual_content_id:-missing}" >&2
  exit 65
}

source_labels="$(${docker_bin} image inspect --format '{{json .Config.Labels}}' "${image_ref}")"
SOURCE_LABELS="${source_labels}" SOURCE_COMMIT="${source_commit}" "${python_bin}" - <<'PY'
import json, os
labels = json.loads(os.environ['SOURCE_LABELS'])
if not isinstance(labels, dict):
    raise SystemExit('World source image has no labels')
expected = {
    'org.ssafy-festa.component': 'game',
    'org.ssafy-festa.source-commit': os.environ['SOURCE_COMMIT'],
    'org.ssafy-festa.managed': 'true',
}
for key, value in expected.items():
    if labels.get(key) != value:
        raise SystemExit(f'World source image label mismatch for {key}: expected={value!r} actual={labels.get(key)!r}')
PY

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT HUP INT TERM
existing_metadata="${work}/existing.json"
http_code="$(curl --silent --show-error --location --output "${existing_metadata}" --write-out '%{http_code}' --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" "${metadata_url}")"
case "${http_code}" in
  200)
    existing_sha="$("${python_bin}" - "${existing_metadata}" "${source_commit}" "${release_id}" "${image_ref}" "${expected_content_id}" "${package_url}" <<'PY'
import json, pathlib, re, sys
path, source_commit, release_id, image_ref, content_id, package_url = sys.argv[1:]
doc = json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
expected = {
  'schemaVersion':'1.0.0', 'packageName':'festa-world', 'packageVersion':release_id,
  'sourceCommit':source_commit, 'sourceBranch':'develop', 'imageRef':image_ref,
  'imageContentId':content_id, 'packageUrl':package_url,
}
for key, value in expected.items():
    if doc.get(key) != value:
        raise SystemExit(f'existing World package metadata mismatch for {key}: expected={value!r} actual={doc.get(key)!r}')
archive_sha = doc.get('archiveSha256')
if not isinstance(archive_sha, str) or not re.fullmatch(r'[0-9a-f]{64}', archive_sha):
    raise SystemExit('existing World package metadata has invalid archiveSha256')
print(archive_sha)
PY
)"
    existing_sidecar="${work}/existing.sha256"
    sidecar_code="$(curl --silent --show-error --location --output "${existing_sidecar}" --write-out '%{http_code}' --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" "${sha_url}")"
    [[ "${sidecar_code}" == 200 ]] || { echo 'existing World metadata exists but checksum sidecar is missing' >&2; exit 66; }
    read -r sidecar_sha sidecar_name _ < "${existing_sidecar}"
    [[ "${sidecar_sha}" == "${existing_sha}" ]] || { echo 'existing World checksum sidecar disagrees with metadata' >&2; exit 65; }
    [[ "${sidecar_name}" == "${filename}" ]] || { echo 'existing World checksum sidecar has another filename' >&2; exit 65; }
    echo "WORLD_RELEASE_EXISTS: ${release_id} ${existing_sha} ${expected_content_id}"
    exit 0
    ;;
  404) ;;
  *) echo "World package metadata lookup failed: HTTP ${http_code}" >&2; exit 69 ;;
esac

archive="${work}/${filename}"
"${docker_bin}" image save --output "${archive}" "${image_ref}"

"${python_bin}" - "${archive}" "${image_ref}" "${source_commit}" <<'PY'
import json, pathlib, tarfile, sys
archive_path = pathlib.Path(sys.argv[1]); expected_ref = sys.argv[2]; expected_source_commit = sys.argv[3]
with tarfile.open(archive_path, 'r:*') as archive:
    try: member = archive.getmember('manifest.json')
    except KeyError: raise SystemExit('World archive has no manifest.json')
    stream = archive.extractfile(member)
    if stream is None: raise SystemExit('World archive manifest.json is unreadable')
    manifest = json.load(stream)
    matches = [item for item in manifest if expected_ref in (item.get('RepoTags') or [])]
    if len(matches) != 1: raise SystemExit('World archive does not contain exactly one expected image tag')
    config_name = matches[0].get('Config')
    if not isinstance(config_name, str) or not config_name: raise SystemExit('World archive image has no Docker config')
    try: config_member = archive.getmember(config_name)
    except KeyError: raise SystemExit('World archive Docker config is missing')
    config_stream = archive.extractfile(config_member)
    if config_stream is None: raise SystemExit('World archive Docker config is unreadable')
    config = json.load(config_stream)
    labels = ((config.get('config') or {}).get('Labels') or {})
    expected = {
      'org.ssafy-festa.component':'game',
      'org.ssafy-festa.source-commit':expected_source_commit,
      'org.ssafy-festa.managed':'true',
    }
    for key, value in expected.items():
        if labels.get(key) != value:
            raise SystemExit(f'World archive label mismatch for {key}: expected={value!r} actual={labels.get(key)!r}')
PY

archive_sha="$(sha256sum "${archive}" | awk '{print $1}')"
printf '%s  %s\n' "${archive_sha}" "${filename}" > "${work}/${sha_filename}"
SOURCE_COMMIT="${source_commit}" RELEASE_ID="${release_id}" IMAGE_REF="${image_ref}" IMAGE_CONTENT_ID="${expected_content_id}" ARCHIVE_SHA256="${archive_sha}" PACKAGE_URL="${package_url}" "${python_bin}" - "${work}/${metadata_filename}" <<'PY'
import datetime, json, os, pathlib, sys
path = pathlib.Path(sys.argv[1])
doc = {
 'schemaVersion':'1.0.0','packageName':'festa-world','packageVersion':os.environ['RELEASE_ID'],
 'sourceCommit':os.environ['SOURCE_COMMIT'],'sourceBranch':'develop','imageRef':os.environ['IMAGE_REF'],
 'imageContentId':os.environ['IMAGE_CONTENT_ID'],'archiveSha256':os.environ['ARCHIVE_SHA256'],
 'packageUrl':os.environ['PACKAGE_URL'],
 'createdAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),
}
path.write_text(json.dumps(doc, indent=2)+'\n', encoding='utf-8')
PY
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" --upload-file "${archive}" "${package_url}"
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" --upload-file "${work}/${sha_filename}" "${sha_url}"
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" --upload-file "${work}/${metadata_filename}" "${metadata_url}"
echo "PUBLISHED_WORLD_RELEASE: ${release_id} ${archive_sha} ${expected_content_id}"
