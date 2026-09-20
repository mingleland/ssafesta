#!/usr/bin/env bash
# 같은 source 의 Unity 산출물을 다시 만들지 않기 위해, Registry 와 로컬(transfer dir·docker) 상태로 다음 단계를 정한다 (Batch 2).
#
#   BUILD_REQUIRED   Registry 에 둘 다 없고 로컬도 완전하지 않다 → Unity build
#   PUBLISH_BOTH     Registry 에 둘 다 없고 로컬 zip + image 가 있다 → build 없이 publish
#   PUBLISH_WEBGL    world 만 Registry 에 있고 로컬 zip 이 있다 → webgl 만 publish
#   PUBLISH_WORLD    webgl 만 Registry 에 있고 로컬 image 가 있다 → world 만 publish
#   SKIP_TO_DEPLOY   Registry 에 둘 다 있다 → build/publish 없이 deploy
#   exit 65          한쪽만 Registry 에 있는데 없는 쪽을 로컬에서도 못 채운다(PARTIAL_REGISTRY) → rebuild 금지, 사람이 본다
set -euo pipefail
set +x
usage() { echo 'Usage: resolve-game-artifacts.sh --source-commit SHA --webgl-dir DIR' >&2; exit 64; }
source_commit='' webgl_dir=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --webgl-dir) webgl_dir="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ && -n "${webgl_dir}" ]] || usage
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
docker_bin="${DOCKER_BIN:-docker}"
gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
release_id="${source_commit:0:8}"
base="${gitlab_api%/}/projects/${project_id}/packages/generic"
webgl_name="festa-webgl-release-${release_id}.zip"
webgl_sha_url="${base}/${WEBGL_PACKAGE_NAME:-festa-webgl}/${release_id}/${webgl_name}.sha256"
world_json_url="${base}/${WORLD_PACKAGE_NAME:-festa-world}/${release_id}/festa-world-release-${release_id}.json"

work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT HUP INT TERM
lookup() { # url out -> http code (200/404 만 정상)
  local code
  code="$(curl --silent --show-error --location --output "$2" --write-out '%{http_code}' --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" "$1")"
  [[ "${code}" == 200 || "${code}" == 404 ]] || { echo "package lookup failed: HTTP ${code} for $1" >&2; exit 69; }
  echo "${code}"
}
registry_webgl_sha=''
if [[ "$(lookup "${webgl_sha_url}" "${work}/webgl.sha256")" == 200 ]]; then
  read -r registry_webgl_sha sidecar_name _ <"${work}/webgl.sha256"
  [[ "${sidecar_name:-}" == "${webgl_name}" && "${registry_webgl_sha}" =~ ^[0-9a-f]{64}$ ]] || { echo "registry festa-webgl/${release_id} sidecar is malformed" >&2; exit 65; }
fi
registry_world_sha=''
if [[ "$(lookup "${world_json_url}" "${work}/world.json")" == 200 ]]; then
  registry_world_sha="$("${PYTHON_BIN:-python3}" -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("sourceCommit")==sys.argv[2], "registry world metadata belongs to another commit"; print(d["archiveSha256"])' "${work}/world.json" "${source_commit}")"
fi

local_webgl=false
if [[ -f "${webgl_dir}/${webgl_name}" && -f "${webgl_dir}/${webgl_name}.sha256" ]]; then
  read -r local_sha _ <"${webgl_dir}/${webgl_name}.sha256"
  [[ "$(sha256sum "${webgl_dir}/${webgl_name}" | awk '{print $1}')" == "${local_sha}" ]] && local_webgl=true
fi
local_world=false
image_ref="festa-game:${source_commit}"
content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}" 2>/dev/null || true)"
[[ "${content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] && local_world=true

decision=''
if [[ -n "${registry_webgl_sha}" && -n "${registry_world_sha}" ]]; then decision=SKIP_TO_DEPLOY
elif [[ -z "${registry_webgl_sha}" && -z "${registry_world_sha}" ]]; then
  if [[ "${local_webgl}" == true && "${local_world}" == true ]]; then decision=PUBLISH_BOTH; else decision=BUILD_REQUIRED; fi
elif [[ -n "${registry_world_sha}" ]]; then
  [[ "${local_webgl}" == true ]] || { echo "PARTIAL_REGISTRY: festa-world/${release_id} exists but festa-webgl/${release_id} does not and no local zip is available in ${webgl_dir}; rebuilding an already-published source is not allowed" >&2; exit 65; }
  decision=PUBLISH_WEBGL
else
  [[ "${local_world}" == true ]] || { echo "PARTIAL_REGISTRY: festa-webgl/${release_id} exists but festa-world/${release_id} does not and image ${image_ref} is not present locally; rebuilding an already-published source is not allowed" >&2; exit 65; }
  decision=PUBLISH_WORLD
fi
printf '{"schemaVersion":"1.0.0","decision":"%s","releaseId":"%s","sourceCommit":"%s","registry":{"webglSha256":"%s","worldSha256":"%s"},"local":{"webgl":%s,"world":%s,"imageRef":"%s","contentId":"%s"}}\n' \
  "${decision}" "${release_id}" "${source_commit}" "${registry_webgl_sha}" "${registry_world_sha}" "${local_webgl}" "${local_world}" "${image_ref}" "${content_id}"
