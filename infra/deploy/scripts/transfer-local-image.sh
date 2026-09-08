#!/usr/bin/env bash
# rootless CI Docker의 불변 이미지를 rootful 배포 Docker로 전달한다.
set -euo pipefail

docker_bin="${DOCKER_BIN:-docker}"
: "${BUILD_DOCKER_HOST:?}" "${DEPLOY_DOCKER_HOST:?}" "${IMAGE_REF:?}" "${CONTENT_ID:?}"
[[ "${CONTENT_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'invalid image content ID' >&2; exit 64; }

source_id="$(${docker_bin} --host "${BUILD_DOCKER_HOST}" image inspect --format '{{.Id}}' "${IMAGE_REF}")"
[[ "${source_id}" == "${CONTENT_ID}" ]] || { echo 'source image content ID mismatch' >&2; exit 65; }

target_id="$(${docker_bin} --host "${DEPLOY_DOCKER_HOST}" image inspect --format '{{.Id}}' "${IMAGE_REF}" 2>/dev/null || true)"
transferred=false
if [[ "${target_id}" != "${CONTENT_ID}" ]]; then
  "${docker_bin}" --host "${BUILD_DOCKER_HOST}" image save "${IMAGE_REF}" |
    "${docker_bin}" --host "${DEPLOY_DOCKER_HOST}" image load >/dev/null
  transferred=true
fi

target_id="$(${docker_bin} --host "${DEPLOY_DOCKER_HOST}" image inspect --format '{{.Id}}' "${IMAGE_REF}")"
[[ "${target_id}" == "${CONTENT_ID}" ]] || { echo 'deployed image content ID mismatch' >&2; exit 65; }
printf '{"imageRef":"%s","contentId":"%s","transferred":%s}\n' "${IMAGE_REF}" "${CONTENT_ID}" "${transferred}"
