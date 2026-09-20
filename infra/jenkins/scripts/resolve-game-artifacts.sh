#!/usr/bin/env bash
# Jenkins 는 Unity Editor 를 돌리지 않는다 (Batch 2 Consumer-only). Registry 상태만 보고 game 구간의 다음 단계를 정한다.
#
#   REGISTRY_COMPLETE            canonical festa-webgl/<8sha> + festa-world/<8sha> 둘 다 있다 → 그대로 deploy
#   BUNDLE_AVAILABLE             canonical 이 없고 Unity Release Bundle(unity-release-bundle/<8sha>)이 있다 → validate → publish 둘 다 → deploy
#   PUBLISH_WEBGL / PUBLISH_WORLD canonical 한쪽만 있고 bundle 이 있다 → 없는 쪽만 bundle 에서 publish
#   WAITING_FOR_UNITY_ARTIFACT   canonical 도 bundle 도 없다 → 아무것도 만들지 않고 정상 종료(대기)
#   exit 65                      canonical 한쪽만 있는데 bundle 이 없다(PARTIAL_REGISTRY) → 사람이 본다
set -euo pipefail
set +x
usage() { echo 'Usage: resolve-game-artifacts.sh --source-commit SHA' >&2; exit 64; }
source_commit=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
release_id="${source_commit:0:8}"
base="${gitlab_api%/}/projects/${project_id}/packages/generic"
webgl_name="festa-webgl-release-${release_id}.zip"
webgl_sha_url="${base}/${WEBGL_PACKAGE_NAME:-festa-webgl}/${release_id}/${webgl_name}.sha256"
world_json_url="${base}/${WORLD_PACKAGE_NAME:-festa-world}/${release_id}/festa-world-release-${release_id}.json"
bundle_meta_url="${base}/${UNITY_BUNDLE_PACKAGE_NAME:-unity-release-bundle}/${release_id}/image-metadata.json"

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
registry_world_sha='' registry_world_content_id=''
if [[ "$(lookup "${world_json_url}" "${work}/world.json")" == 200 ]]; then
  read -r registry_world_sha registry_world_content_id < <("${PYTHON_BIN:-python3}" -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("sourceCommit")==sys.argv[2], "registry world metadata belongs to another commit"; print(d["archiveSha256"], d["imageContentId"])' "${work}/world.json" "${source_commit}")
fi
bundle=false
if [[ "$(lookup "${bundle_meta_url}" "${work}/bundle.json")" == 200 ]]; then
  "${PYTHON_BIN:-python3}" -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("sourceCommit")==sys.argv[2], "bundle image-metadata belongs to another commit"' "${work}/bundle.json" "${source_commit}"
  bundle=true
fi

decision=''
if [[ -n "${registry_webgl_sha}" && -n "${registry_world_sha}" ]]; then decision=REGISTRY_COMPLETE
elif [[ -z "${registry_webgl_sha}" && -z "${registry_world_sha}" ]]; then
  if [[ "${bundle}" == true ]]; then decision=BUNDLE_AVAILABLE; else decision=WAITING_FOR_UNITY_ARTIFACT; fi
elif [[ -n "${registry_world_sha}" ]]; then
  [[ "${bundle}" == true ]] || { echo "PARTIAL_REGISTRY: festa-world/${release_id} exists but festa-webgl/${release_id} does not and no Unity Release Bundle is available; a published source is never rebuilt" >&2; exit 65; }
  decision=PUBLISH_WEBGL
else
  [[ "${bundle}" == true ]] || { echo "PARTIAL_REGISTRY: festa-webgl/${release_id} exists but festa-world/${release_id} does not and no Unity Release Bundle is available; a published source is never rebuilt" >&2; exit 65; }
  decision=PUBLISH_WORLD
fi
printf '{"schemaVersion":"1.0.0","decision":"%s","releaseId":"%s","sourceCommit":"%s","registry":{"webglSha256":"%s","worldSha256":"%s","worldContentId":"%s"},"bundle":%s}\n' \
  "${decision}" "${release_id}" "${source_commit}" "${registry_webgl_sha}" "${registry_world_sha}" "${registry_world_content_id}" "${bundle}"
