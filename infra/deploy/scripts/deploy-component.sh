#!/usr/bin/env bash
set -euo pipefail
docker_bin="${DOCKER_BIN:-docker}"
: "${CI_COMPONENT:?}" "${COMPOSE_FILE:?}" "${COMPOSE_PROJECT:?}" "${COMPOSE_SERVICE:?}" "${IMAGE_REF:?}" "${CONTENT_ID:?}"
[[ "${CI_COMPONENT}" == "${COMPOSE_SERVICE}" ]] || { echo 'component/service mismatch' >&2; exit 64; }
[[ "${COMPOSE_PROJECT}" == "festa-dev-${CI_COMPONENT}" ]] || { echo 'unexpected compose project' >&2; exit 64; }
[[ -f "${COMPOSE_FILE}" ]] || { echo 'compose file missing' >&2; exit 66; }
if [[ "${CI_COMPONENT}" == ai || "${CI_COMPONENT}" == back ]]; then
  : "${COMPONENT_ENV_FILE:?}" "${INTERNAL_AI_TO_SPRING_TOKENS:?}"
  [[ -f "${COMPONENT_ENV_FILE}" ]] || { echo 'component runtime env credential file missing' >&2; exit 66; }
fi
actual="$(${docker_bin} image inspect --format '{{.Id}}' "${IMAGE_REF}")"
[[ "${actual}" == "${CONTENT_ID}" ]] || { echo 'image content ID mismatch' >&2; exit 65; }
export COMPONENT_IMAGE_REF="${IMAGE_REF}"
"${docker_bin}" compose --project-name "${COMPOSE_PROJECT}" --file "${COMPOSE_FILE}" up -d --no-deps --wait "${COMPOSE_SERVICE}"
printf '{"component":"%s","project":"%s","service":"%s","imageRef":"%s","contentId":"%s"}\n' \
  "${CI_COMPONENT}" "${COMPOSE_PROJECT}" "${COMPOSE_SERVICE}" "${IMAGE_REF}" "${CONTENT_ID}"
