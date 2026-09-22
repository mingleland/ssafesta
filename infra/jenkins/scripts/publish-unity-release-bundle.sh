#!/usr/bin/env bash
# Unity Release Bundle 4개 파일을 Registry(unity-release-bundle/<8sha>)에 올리고, 그 즉시 develop Demo 배포를 깨운다.
#
# Registry 업로드 자체는 GitLab 이벤트를 만들지 않는다. 사람이 Jenkins 를 눌러야 한다면 "자동화" 라고 부를 수 없으므로
# 업로드의 마지막 단계에서 job 을 호출하는 것까지가 이 스크립트의 책임이다.
#
# 멱등: 같은 version 에 같은 bytes 면 BUNDLE_EXISTS(0), 다른 bytes 면 65. 한 source 에 두 바이너리는 조용히 생기면 안 된다.
set -euo pipefail
set +x

usage() {
  echo 'Usage: publish-unity-release-bundle.sh --bundle-dir DIR --source-commit SHA40 [--fixture] [--target demo] [--no-trigger]' >&2
  exit 64
}
bundle_dir='' source_commit='' fixture=false target=demo trigger=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-dir) bundle_dir="${2:-}"; shift 2 ;;
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --fixture) fixture=true; shift ;;
    --target) target="${2:-}"; shift 2 ;;
    --no-trigger) trigger=0; shift ;;
    *) usage ;;
  esac
done
[[ -d "${bundle_dir}" && "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "${target}" == demo ]] || { echo 'only the demo target is supported' >&2; exit 64; }
: "${GITLAB_PACKAGE_TOKEN:?GITLAB_PACKAGE_TOKEN is required}"

python_bin="${PYTHON_BIN:-python3}"
curl_bin="${CURL_BIN:-curl}"
gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
release_id="${source_commit:0:8}"
package_base="${gitlab_api%/}/projects/${project_id}/packages/generic/${UNITY_BUNDLE_PACKAGE_NAME:-unity-release-bundle}/${release_id}"
zip_name="festa-webgl-release-${release_id}.zip"
tar_name="festa-game-${release_id}.tar"
files=("${zip_name}" "${tar_name}" webgl-manifest.json image-metadata.json)
for name in "${files[@]}"; do
  [[ -f "${bundle_dir}/${name}" ]] || { echo "bundle file missing: ${name}" >&2; exit 66; }
done

# 올리기 전에 계약부터 본다 — Registry 에 들어간 뒤에 틀린 것을 알면 그 version 은 영원히 오염된다.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
read -r _ zip_commit zip_sha < <(bash "${script_dir}/validate-webgl-archive.sh" "${bundle_dir}/${zip_name}" --manifest "${bundle_dir}/webgl-manifest.json" --source-commit "${source_commit}")
content_id="$("${python_bin}" -c 'import json,sys; print(json.load(open(sys.argv[1]))["contentId"])' "${bundle_dir}/image-metadata.json")"
read -r _ tar_commit _ < <(bash "${script_dir}/validate-game-image-archive.sh" "${bundle_dir}/${tar_name}" --image-ref "festa-game:${source_commit}" --content-id "${content_id}" --source-commit "${source_commit}")
[[ "${zip_commit}" == "${tar_commit}" ]] || { echo "BUNDLE_LINEAGE_MISMATCH: zip ${zip_commit} != image ${tar_commit}" >&2; exit 65; }

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT HUP INT TERM
( cd "${bundle_dir}" && sha256sum "${files[@]}" ) >"${work}/bundle.sha256"

# 이미 같은 version 이 있는가 — 묶음 체크섬 하나만 읽고 판단한다.
code="$("${curl_bin}" --silent --show-error --location --output "${work}/existing.sha256" --write-out '%{http_code}' \
  --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" "${package_base}/bundle.sha256")"
case "${code}" in
  200)
    if diff -q "${work}/existing.sha256" "${work}/bundle.sha256" >/dev/null; then
      echo "BUNDLE_EXISTS: unity-release-bundle/${release_id} (identical bytes)"
    else
      echo "BUNDLE_IDENTITY_COLLISION: unity-release-bundle/${release_id} already holds other bytes" >&2
      exit 65
    fi
    ;;
  404)
    for name in "${files[@]}" ; do
      "${curl_bin}" --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
        --upload-file "${bundle_dir}/${name}" "${package_base}/${name}"
    done
    "${curl_bin}" --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
      --upload-file "${work}/bundle.sha256" "${package_base}/bundle.sha256"
    echo "PUBLISHED_UNITY_BUNDLE: ${release_id} ${zip_sha} ${content_id}"
    ;;
  *) echo "bundle lookup failed: HTTP ${code}" >&2; exit 69 ;;
esac

if (( trigger )); then
  [[ "${fixture}" == false ]] || { echo 'fixture bundle publishing requires --no-trigger' >&2; exit 64; }
  : "${JENKINS_URL:?JENKINS_URL is required}"
  : "${JENKINS_USER:?JENKINS_USER is required}"
  : "${JENKINS_API_TOKEN:?JENKINS_API_TOKEN is required}"
  job="${JENKINS_DEVELOP_DEPLOY_JOB:-festa-gitlab-develop/job/develop}"
  # API token 이면 crumb 이 필요 없지만 비밀번호 인증이면 필요하다 — 있으면 붙이고 없으면 그냥 간다.
  crumb="$("${curl_bin}" --silent --show-error --cookie-jar "${work}/jenkins.cookie" \
    --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" "${JENKINS_URL%/}/crumbIssuer/api/json" 2>/dev/null \
    | "${python_bin}" -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"] + ":" + d["crumb"])' 2>/dev/null || true)"
  "${curl_bin}" --fail --silent --show-error --request POST \
    --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" \
    --cookie "${work}/jenkins.cookie" \
    ${crumb:+--header "${crumb}"} \
    --data-urlencode "UNITY_ARTIFACT_CANDIDATE=${source_commit}" \
    --data-urlencode 'DEPLOY_GAME_TO_DEMO=true' \
    --data-urlencode 'CHANGE_BASE_SHA=' \
    "${JENKINS_URL%/}/job/${job}/buildWithParameters" >/dev/null
  echo "TRIGGERED_DEMO_DEPLOY: ${job} UNITY_ARTIFACT_CANDIDATE=${source_commit} DEPLOY_GAME_TO_DEMO=true"
fi
