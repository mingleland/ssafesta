#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
publisher="${repo_root}/infra/jenkins/scripts/publish-world-release.sh"; loader="${repo_root}/infra/deploy/scripts/load-production-world.sh"
fail(){ echo "FAIL: $*" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/bin" "${work}/remote"
source_commit='0123456789abcdef0123456789abcdef01234567'; release_id="${source_commit:0:8}"; image_ref="festa-game:${source_commit}"; runtime_content_id="sha256:$(printf 'd%.0s' {1..64})"
SOURCE_COMMIT="${source_commit}" IMAGE_REF="${image_ref}" WORK="${work}" python3 <<'PY'
import hashlib, io, json, os, pathlib, tarfile
work=pathlib.Path(os.environ['WORK']); source_commit=os.environ['SOURCE_COMMIT']; image_ref=os.environ['IMAGE_REF']
config=json.dumps({'architecture':'amd64','os':'linux','config':{'Labels':{'org.ssafy-festa.component':'game','org.ssafy-festa.managed':'true','org.ssafy-festa.source-commit':source_commit}}},separators=(',',':')).encode()
(work/'archive-config-digest').write_text('sha256:'+hashlib.sha256(config).hexdigest()+'\n')
manifest=json.dumps([{'Config':'config.json','RepoTags':[image_ref],'Layers':[]}],separators=(',',':')).encode()
with tarfile.open(work/'fixture.tar','w') as archive:
  for name,body in [('manifest.json',manifest),('config.json',config)]:
    info=tarfile.TarInfo(name); info.size=len(body); info.mtime=0; archive.addfile(info,io.BytesIO(body))
PY
archive_config_digest="$(cat "${work}/archive-config-digest")"; [[ "${runtime_content_id}" != "${archive_config_digest}" ]] || fail 'fixture identity domains collapsed'
cat >"${work}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG}"
case "${1:-} ${2:-}" in
'image inspect')
  case "${4:-}" in
    '{{.Id}}') [[ -f "${FAKE_LOADED_MARKER}" ]] && printf '%s\n' "${FAKE_LOADED_CONTENT_ID}" || printf '%s\n' "${FAKE_SOURCE_CONTENT_ID}";;
    '{{json .Config.Labels}}') printf '%s\n' "${FAKE_LABELS_JSON}";;
    *) exit 64;;
  esac;;
'image save') [[ "${3:-}" == --output ]]; cp "${FAKE_ARCHIVE_SOURCE}" "${4:?}";;
'image load') [[ "${3:-}" == --input && -f "${4:?}" ]]; : >"${FAKE_LOADED_MARKER}";;
*) exit 64;;
esac
SH
chmod +x "${work}/bin/docker"
cat >"${work}/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output=''; write_out=''; upload=''; fail_mode=0; url=''
while [[ $# -gt 0 ]]; do case "$1" in --output) output="${2:-}";shift 2;; --write-out) write_out="${2:-}";shift 2;; --upload-file) upload="${2:-}";shift 2;; --header) shift 2;; --fail|--fail-with-body) fail_mode=1;shift;; --silent|--show-error|--location) shift;; -*) exit 64;; *) url="$1";shift;; esac; done
[[ -n "${url}" ]]; relative="${url#*/packages/generic/}"; [[ "${relative}" != "${url}" ]]; target="${FAKE_REMOTE_ROOT}/${relative}"; code=200
if [[ -n "${upload}" ]]; then mkdir -p "$(dirname "${target}")"; cp "${upload}" "${target}"; code=201; else if [[ -f "${target}" ]]; then [[ -z "${output}" ]] || cp "${target}" "${output}"; code=200; else [[ -z "${output}" ]] || : >"${output}"; code=404; fi; fi
[[ -z "${write_out}" ]] || printf '%s' "${code}"; (( ! fail_mode || code < 400 )) || exit 22
SH
chmod +x "${work}/bin/curl"
export PATH="${work}/bin:${PATH}" FAKE_SOURCE_CONTENT_ID="${runtime_content_id}" FAKE_LOADED_CONTENT_ID="${runtime_content_id}" FAKE_LOADED_MARKER="${work}/loaded.marker" FAKE_ARCHIVE_SOURCE="${work}/fixture.tar" FAKE_DOCKER_LOG="${work}/docker.log" FAKE_REMOTE_ROOT="${work}/remote" GITLAB_PACKAGE_TOKEN=x GITLAB_DEPLOY_TOKEN=y
export FAKE_LABELS_JSON="$(SOURCE_COMMIT="${source_commit}" python3 - <<'PY'
import json,os
print(json.dumps({'org.ssafy-festa.component':'game','org.ssafy-festa.managed':'true','org.ssafy-festa.source-commit':os.environ['SOURCE_COMMIT']}))
PY
)"
package_url="https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/festa-world/${release_id}/festa-world-release-${release_id}.tar"
publish_output="$(DOCKER_BIN=docker PYTHON_BIN=python3 "${publisher}" --source-commit "${source_commit}" --image-ref "${image_ref}" --content-id "${runtime_content_id}")"; [[ "${publish_output}" == PUBLISHED_WORLD_RELEASE:* ]] || fail publish
remote_root="${work}/remote/festa-world/${release_id}"; archive_remote="${remote_root}/festa-world-release-${release_id}.tar"; sidecar_remote="${archive_remote}.sha256"; metadata_remote="${remote_root}/festa-world-release-${release_id}.json"
[[ -f "${archive_remote}" && -f "${sidecar_remote}" && -f "${metadata_remote}" ]] || fail files
archive_sha="$(sha256sum "${archive_remote}"|awk '{print $1}')"
: >"${work}/docker.log"; publish_again="$(DOCKER_BIN=docker PYTHON_BIN=python3 "${publisher}" --source-commit "${source_commit}" --image-ref "${image_ref}" --content-id "${runtime_content_id}")"; [[ "${publish_again}" == WORLD_RELEASE_EXISTS:* ]] || fail idempotent; ! grep -q '^image save ' "${work}/docker.log" || fail save-again
rm -f "${FAKE_LOADED_MARKER}"; : >"${work}/docker.log"; load_output="$(DOCKER_BIN=docker PYTHON_BIN=python3 "${loader}" --release-id "${release_id}" --sha256 "${archive_sha}" --package-url "${package_url}" --image-ref "${image_ref}" --content-id "${runtime_content_id}")"; [[ "${load_output}" == "LOADED_WORLD_RELEASE: ${release_id} ${archive_sha} ${runtime_content_id}" ]] || fail load
rm -f "${FAKE_LOADED_MARKER}"; : >"${work}/docker.log"; if DOCKER_BIN=docker PYTHON_BIN=python3 "${loader}" --release-id "${release_id}" --sha256 "$(printf '0%.0s' {1..64})" --package-url "${package_url}" --image-ref "${image_ref}" --content-id "${runtime_content_id}" >/dev/null 2>&1; then fail badsha; fi; ! grep -q '^image load ' "${work}/docker.log" || fail badsha-load
cp "${metadata_remote}" "${work}/meta.good"; python3 - "${metadata_remote}" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d['imageContentId']='sha256:'+'9'*64; p.write_text(json.dumps(d))
PY
rm -f "${FAKE_LOADED_MARKER}"; : >"${work}/docker.log"; if DOCKER_BIN=docker PYTHON_BIN=python3 "${loader}" --release-id "${release_id}" --sha256 "${archive_sha}" --package-url "${package_url}" --image-ref "${image_ref}" --content-id "${runtime_content_id}" >/dev/null 2>&1; then fail badmeta; fi; ! grep -q '^image load ' "${work}/docker.log" || fail badmeta-load; cp "${work}/meta.good" "${metadata_remote}"
export FAKE_LOADED_CONTENT_ID="sha256:$(printf '9%.0s' {1..64})"; rm -f "${FAKE_LOADED_MARKER}"; : >"${work}/docker.log"; if DOCKER_BIN=docker PYTHON_BIN=python3 "${loader}" --release-id "${release_id}" --sha256 "${archive_sha}" --package-url "${package_url}" --image-ref "${image_ref}" --content-id "${runtime_content_id}" >/dev/null 2>&1; then fail badloaded; fi; grep -q '^image load --input ' "${work}/docker.log" || fail badloaded-no-load
printf '%s\n' 'PASS: World package preserves approved Demo runtime identity and immutable archive provenance without rebuild'
