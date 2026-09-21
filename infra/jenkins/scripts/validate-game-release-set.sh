#!/usr/bin/env bash
# WebGL zip 과 World image 가 한 release set 인지 확인한다 (Batch 2): 같은 sourceCommit, zip sha 가 기대값과 같고, image contentId 가 기대값과 같다.
# 같은 commit 이면 NGO prefab tree 도 같으므로 deploy-game.sh 의 prefab guard 는 자동으로 통과한다.
set -euo pipefail
usage() { echo 'Usage: validate-game-release-set.sh --source-commit SHA --webgl-zip ZIP --webgl-sha256 HEX --image-ref REF --content-id sha256:HEX' >&2; exit 64; }
source_commit='' webgl_zip='' webgl_sha='' image_ref='' content_id=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --webgl-zip) webgl_zip="${2:-}"; shift 2 ;;
    --webgl-sha256) webgl_sha="${2,,}"; shift 2 ;;
    --image-ref) image_ref="${2:-}"; shift 2 ;;
    --content-id) content_id="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ && -n "${webgl_zip}" && "${webgl_sha}" =~ ^[0-9a-f]{64}$ && -n "${image_ref}" && "${content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || usage
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker_bin="${DOCKER_BIN:-docker}"
read -r _ zip_commit zip_sha < <(bash "${script_dir}/validate-webgl-archive.sh" "${webgl_zip}" --source-commit "${source_commit}")
[[ "${zip_sha}" == "${webgl_sha}" ]] || { echo "RELEASE_SET_MISMATCH: webgl zip sha ${zip_sha} != expected ${webgl_sha}" >&2; exit 65; }
actual_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}" 2>/dev/null || true)"
[[ "${actual_id}" == "${content_id}" ]] || { echo "RELEASE_SET_MISMATCH: ${image_ref} contentId ${actual_id:-missing} != expected ${content_id}" >&2; exit 65; }
label="$(${docker_bin} image inspect --format '{{index .Config.Labels "org.ssafy-festa.source-commit"}}' "${image_ref}")"
[[ "${label}" == "${zip_commit}" ]] || { echo "RELEASE_SET_MISMATCH: image source-commit ${label} != webgl sourceCommit ${zip_commit}" >&2; exit 65; }
echo "GAME_RELEASE_SET_OK ${source_commit:0:8} ${webgl_sha} ${content_id}"
