#!/usr/bin/env bash
# Unity Release Bundle 을 Registry(unity-release-bundle/<8sha>)에서 받아 검증하고 World image 를 로컬 docker 에 올린다 (Batch 2 Consumer-only).
#
# Bundle = Unity 담당자의 정상 개발환경에서 나온 4개 파일(이미 실물로 받은 형식 그대로):
#   festa-webgl-release-<8sha>.zip   festa-game-<8sha>.tar   webgl-manifest.json   image-metadata.json
# 검증: zip 구조·내부 manifest == webgl-manifest.json·sourceCommit, tar 의 RepoTags/label/contentId == image-metadata.json,
#       zip 과 image 가 같은 commit. 그 다음에만 docker image load → 로드된 image 의 .Id/label 재확인.
# 출력: BUNDLE_OK <sourceCommit> <zipSha256> <contentId>
set -euo pipefail
set +x
usage() { echo 'Usage: intake-unity-release-bundle.sh --source-commit SHA --dest DIR' >&2; exit 64; }
source_commit='' dest=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --dest) dest="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ && -n "${dest}" ]] || usage
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker_bin="${DOCKER_BIN:-docker}"
python_bin="${PYTHON_BIN:-python3}"
gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
release_id="${source_commit:0:8}"
base="${gitlab_api%/}/projects/${project_id}/packages/generic/${UNITY_BUNDLE_PACKAGE_NAME:-unity-release-bundle}/${release_id}"
zip_name="festa-webgl-release-${release_id}.zip"
tar_name="festa-game-${release_id}.tar"
image_ref="festa-game:${source_commit}"

mkdir -p "${dest}"
for name in image-metadata.json webgl-manifest.json "${zip_name}" "${tar_name}"; do
  code="$(curl --silent --show-error --location --output "${dest}/${name}" --write-out '%{http_code}' --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" "${base}/${name}")"
  [[ "${code}" == 200 ]] || { echo "BUNDLE_INCOMPLETE: ${name} → HTTP ${code} (unity-release-bundle/${release_id})" >&2; exit 66; }
done

read -r content_id < <("${python_bin}" - "${dest}/image-metadata.json" "${source_commit}" "${image_ref}" <<'PY'
import json, re, sys
doc = json.load(open(sys.argv[1])); commit, image_ref = sys.argv[2:4]
if doc.get('component') != 'game': raise SystemExit(f'image-metadata component {doc.get("component")!r} != game')
if doc.get('sourceCommit') != commit: raise SystemExit(f'image-metadata sourceCommit {doc.get("sourceCommit")!r} != {commit}')
if doc.get('imageRef') != image_ref: raise SystemExit(f'image-metadata imageRef {doc.get("imageRef")!r} != {image_ref}')
cid = doc.get('contentId')
if not isinstance(cid, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', cid): raise SystemExit('image-metadata contentId is invalid')
print(cid)
PY
)
read -r _ zip_commit zip_sha < <(bash "${script_dir}/validate-webgl-archive.sh" "${dest}/${zip_name}" --manifest "${dest}/webgl-manifest.json" --source-commit "${source_commit}")
read -r _ tar_commit _ < <(bash "${script_dir}/validate-game-image-archive.sh" "${dest}/${tar_name}" --image-ref "${image_ref}" --content-id "${content_id}" --source-commit "${source_commit}")
[[ "${zip_commit}" == "${tar_commit}" ]] || { echo "BUNDLE_LINEAGE_MISMATCH: zip ${zip_commit} != image ${tar_commit}" >&2; exit 65; }

existing="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}" 2>/dev/null || true)"
if [[ -z "${existing}" ]]; then
  "${docker_bin}" image load --input "${dest}/${tar_name}" >/dev/null
  existing="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
fi
[[ "${existing}" == "${content_id}" ]] || { echo "BUNDLE_IMAGE_MISMATCH: loaded ${image_ref} is ${existing}, bundle says ${content_id}" >&2; exit 65; }
label="$(${docker_bin} image inspect --format '{{index .Config.Labels "org.ssafy-festa.source-commit"}}' "${image_ref}")"
[[ "${label}" == "${source_commit}" ]] || { echo "BUNDLE_IMAGE_MISMATCH: loaded image label source-commit=${label:-missing}" >&2; exit 65; }
printf '%s  %s\n' "${zip_sha}" "${zip_name}" >"${dest}/${zip_name}.sha256"
echo "BUNDLE_OK ${source_commit} ${zip_sha} ${content_id}"
