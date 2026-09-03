#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

config_only=0
if [[ "${1:-}" == '--config-only' ]]; then
  config_only=1
elif [[ $# -gt 0 ]]; then
  fail "usage: $0 [--config-only]"
fi

: "${GAME_IMAGE_REF:?GAME_IMAGE_REF is required}"
: "${CONNECTION_TOKEN_SECRET_FILE:?CONNECTION_TOKEN_SECRET_FILE is required}"

if [[ ! "${GAME_IMAGE_REF}" =~ @sha256:[0-9a-f]{64}$ && ! "${GAME_IMAGE_REF}" =~ :[0-9a-f]{40}$ ]]; then
  fail 'GAME_IMAGE_REF must end in an image digest or full commit SHA tag'
fi

assert_file "${CONNECTION_TOKEN_SECRET_FILE}"
decoded_secret="$(mktemp)"
rendered="$(mktemp)"
cleanup() {
  rm -f -- "${decoded_secret}" "${rendered}"
}
trap cleanup EXIT

if ! base64 -d <"${CONNECTION_TOKEN_SECRET_FILE}" >"${decoded_secret}" 2>/dev/null; then
  fail 'connection token Secret is not valid Base64'
fi
decoded_bytes="$(wc -c <"${decoded_secret}" | tr -d '[:space:]')"
(( decoded_bytes >= 32 )) || fail 'decoded connection token Secret must be at least 32 bytes'
pass 'immutable image reference and Secret shape'

command -v docker >/dev/null 2>&1 || fail 'Docker CLI is required'
docker compose -f "${unity_server_dir}/compose.yaml" config >"${rendered}"
assert_not_contains "${rendered}" '^[[:space:]]+ports:$' 'rendered Compose publishes game port 7777'
pass 'Compose renders without public game port'

if (( config_only == 1 )); then
  exit 0
fi

host_arch="$(uname -m)"
[[ "${host_arch}" == 'x86_64' || "${host_arch}" == 'amd64' ]] \
  || fail "Unity 6000.0.78f1 Dedicated Server requires x86_64 host; got ${host_arch}"
[[ -z "${DOCKER_DEFAULT_PLATFORM:-}" || "${DOCKER_DEFAULT_PLATFORM}" == 'linux/amd64' ]] \
  || fail 'DOCKER_DEFAULT_PLATFORM must not enable a non-amd64 or emulated deployment path'

docker image inspect "${GAME_IMAGE_REF}" >/dev/null 2>&1 || fail 'immutable game image is not available locally'
image_arch="$(docker image inspect --format '{{.Architecture}}' "${GAME_IMAGE_REF}")"
[[ "${image_arch}" == 'amd64' ]] || fail "Unity server image must be amd64; got ${image_arch}"
pass 'host and game image are native x86_64'
docker network inspect "${DEMO_NETWORK_NAME:-festa-demo}" >/dev/null 2>&1 || fail 'demo network does not exist'
pass 'runtime image and demo network exist'

if docker volume inspect "${WORLD_REPLAY_LEDGER_VOLUME:-festa-demo-world-replay}" >/dev/null 2>&1; then
  pass 'replay ledger volume exists'
else
  printf 'INFO: replay ledger volume will be created on first Compose deployment.\n'
fi
