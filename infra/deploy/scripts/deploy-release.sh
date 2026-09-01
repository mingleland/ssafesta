#!/usr/bin/env bash
set -euo pipefail

docker_bin="${DOCKER_BIN:-docker}"
: "${RELEASE_MANIFEST_PATH:?}"
COMPOSE_FILE="${COMPOSE_FILE:-infra/deploy/compose/integration/compose.yaml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-festa-integration}"
[[ "${COMPOSE_PROJECT}" == festa-integration ]] || { echo 'unexpected integration compose project' >&2; exit 64; }
[[ -f "${COMPOSE_FILE}" ]] || { echo "compose file missing: ${COMPOSE_FILE}" >&2; exit 66; }
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

while IFS=$'\t' read -r name image_ref content_id; do
  content_id="${content_id%$'\r'}"
  actual="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
  [[ "${actual}" == "${content_id}" ]] || { echo "image content ID mismatch: ${name} expected=${content_id} actual=${actual}" >&2; exit 65; }
  case "${name}" in
    ai) export AI_IMAGE_REF="${image_ref}";; back) export BACK_IMAGE_REF="${image_ref}";;
    front) export FRONT_IMAGE_REF="${image_ref}";; game) export GAME_IMAGE_REF="${image_ref}";;
    *) echo "unknown component in release: ${name}" >&2; exit 65;;
  esac
done < <(python - "$(native "${RELEASE_MANIFEST_PATH}")" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
for item in d['components']:
 print(item['name'],item['imageRef'],item['contentId'],sep='\t')
PY
)
for required in AI_IMAGE_REF BACK_IMAGE_REF FRONT_IMAGE_REF GAME_IMAGE_REF; do [[ -n "${!required:-}" ]] || { echo "release missing ${required}" >&2; exit 65; }; done

export BACK_BASE_URL="${BACK_BASE_URL:?}" AI_BASE_URL="${AI_BASE_URL:?}" PUBLIC_API_BASE_URL="${PUBLIC_API_BASE_URL:?}"
"${docker_bin}" compose --project-name "${COMPOSE_PROJECT}" --file "${COMPOSE_FILE}" up -d --wait ai back front game
echo "candidate deployed without current pointer mutation"
