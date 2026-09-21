#!/usr/bin/env bash
set -euo pipefail
set +x
usage(){ echo 'Usage: load-production-world.sh --release-id ID --sha256 HEX --package-url URL --image-ref REF --content-id sha256:HEX' >&2; exit 64; }
release_id=''; expected_sha=''; package_url=''; image_ref=''; expected_content_id=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-id) release_id="${2:-}"; shift 2;;
    --sha256) expected_sha="${2:-}"; shift 2;;
    --package-url) package_url="${2:-}"; shift 2;;
    --image-ref) image_ref="${2:-}"; shift 2;;
    --content-id) expected_content_id="${2:-}"; shift 2;;
    *) usage;;
  esac
done
[[ "${release_id}" =~ ^[0-9a-f]{8}$ ]] || usage
[[ "${expected_sha}" =~ ^[0-9a-f]{64}$ ]] || usage
[[ -n "${package_url}" && -n "${image_ref}" ]] || usage
[[ "${expected_content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || usage
expected_filename="festa-world-release-${release_id}.tar"
expected_sha_filename="${expected_filename}.sha256"
expected_metadata_filename="festa-world-release-${release_id}.json"
[[ "${package_url}" == */packages/generic/festa-world/"${release_id}"/"${expected_filename}" ]] || { echo 'Production World package URL does not match release ID' >&2; exit 65; }
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
docker_bin="${DOCKER_BIN:-docker}"; python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT HUP INT TERM
package_base="${package_url%/*}"; metadata_url="${package_base}/${expected_metadata_filename}"; sha_url="${package_base}/${expected_sha_filename}"
metadata="${work}/${expected_metadata_filename}"; sidecar="${work}/${expected_sha_filename}"; archive="${work}/${expected_filename}"
curl --fail --silent --show-error --location --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" --output "${metadata}" "${metadata_url}"
source_commit="$("${python_bin}" - "${metadata}" "${release_id}" "${package_url}" "${expected_sha}" "${image_ref}" "${expected_content_id}" <<'PY'
import json, pathlib, re, sys
metadata_path, release_id, package_url, archive_sha, image_ref, image_content_id = sys.argv[1:]
doc = json.loads(pathlib.Path(metadata_path).read_text(encoding='utf-8'))
expected = {'schemaVersion':'1.0.0','packageName':'festa-world','packageVersion':release_id,'packageUrl':package_url,'archiveSha256':archive_sha,'sourceBranch':'develop','imageRef':image_ref,'imageContentId':image_content_id}
for k,v in expected.items():
    if doc.get(k)!=v: raise SystemExit(f'Production World metadata mismatch for {k}: expected={v!r} actual={doc.get(k)!r}')
source_commit=doc.get('sourceCommit')
if not isinstance(source_commit,str) or not re.fullmatch(r'[0-9a-f]{40}',source_commit): raise SystemExit('Production World metadata has invalid sourceCommit')
if not source_commit.startswith(release_id): raise SystemExit('Production World metadata sourceCommit does not match release ID')
print(source_commit)
PY
)"
curl --fail --silent --show-error --location --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" --output "${sidecar}" "${sha_url}"
read -r sidecar_sha sidecar_name _ < "${sidecar}"
[[ "${sidecar_sha}" == "${expected_sha}" ]] || { echo 'Production World checksum sidecar does not match approved checksum' >&2; exit 65; }
[[ "${sidecar_name}" == "${expected_filename}" ]] || { echo 'Production World checksum sidecar has another filename' >&2; exit 65; }
curl --fail --silent --show-error --location --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" --output "${archive}" "${package_url}"
actual_sha="$(sha256sum "${archive}"|awk '{print $1}')"
[[ "${actual_sha}" == "${expected_sha}" ]] || { echo "Production World archive checksum mismatch: expected=${expected_sha} actual=${actual_sha}" >&2; exit 65; }
"${python_bin}" - "${archive}" "${image_ref}" "${source_commit}" <<'PY'
import json, pathlib, tarfile, sys
archive_path=pathlib.Path(sys.argv[1]); expected_ref=sys.argv[2]; source_commit=sys.argv[3]
with tarfile.open(archive_path,'r:*') as archive:
    try: member=archive.getmember('manifest.json')
    except KeyError: raise SystemExit('Production World archive has no manifest.json')
    stream=archive.extractfile(member)
    if stream is None: raise SystemExit('Production World archive manifest is unreadable')
    manifest=json.load(stream); matches=[x for x in manifest if expected_ref in (x.get('RepoTags') or [])]
    if len(matches)!=1: raise SystemExit('Production World archive does not contain exactly one expected image')
    config_name=matches[0].get('Config')
    if not isinstance(config_name,str) or not config_name: raise SystemExit('Production World archive image has no config')
    try: cm=archive.getmember(config_name)
    except KeyError: raise SystemExit('Production World archive config is missing')
    cs=archive.extractfile(cm)
    if cs is None: raise SystemExit('Production World archive config is unreadable')
    cfg=json.load(cs); labels=((cfg.get('config') or {}).get('Labels') or {})
    expected={'org.ssafy-festa.component':'game','org.ssafy-festa.source-commit':source_commit,'org.ssafy-festa.managed':'true'}
    for k,v in expected.items():
        if labels.get(k)!=v: raise SystemExit(f'Production World archive label mismatch for {k}: expected={v!r} actual={labels.get(k)!r}')
PY
"${docker_bin}" image load --input "${archive}" >/dev/null
loaded_content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
[[ "${loaded_content_id}" == "${expected_content_id}" ]] || { echo "loaded Production World image identity mismatch: expected=${expected_content_id} actual=${loaded_content_id}" >&2; exit 65; }
loaded_labels="$(${docker_bin} image inspect --format '{{json .Config.Labels}}' "${image_ref}")"
LOADED_LABELS="${loaded_labels}" SOURCE_COMMIT="${source_commit}" "${python_bin}" - <<'PY'
import json, os
labels=json.loads(os.environ['LOADED_LABELS'])
if not isinstance(labels,dict): raise SystemExit('loaded Production World image has no labels')
expected={'org.ssafy-festa.component':'game','org.ssafy-festa.source-commit':os.environ['SOURCE_COMMIT'],'org.ssafy-festa.managed':'true'}
for k,v in expected.items():
    if labels.get(k)!=v: raise SystemExit(f'loaded Production World image label mismatch for {k}: expected={v!r} actual={labels.get(k)!r}')
PY
echo "LOADED_WORLD_RELEASE: ${release_id} ${actual_sha} ${loaded_content_id}"
