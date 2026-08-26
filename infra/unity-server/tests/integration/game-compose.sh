#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

compose_file="${unity_server_dir}/compose.yaml"
assert_file "${compose_file}"

assert_contains "${compose_file}" '^[[:space:]]+demo-game:$' 'Compose must define one demo-game service'
assert_contains "${compose_file}" '^[[:space:]]+expose:[[:space:]]+\["7777"\]$' 'game must expose internal 7777'
assert_not_contains "${compose_file}" '^[[:space:]]+ports:' 'game must not publish a host port'
assert_contains "${compose_file}" '^[[:space:]]+user:[[:space:]]+"\$\{GAME_CONTAINER_UID' 'game must run as a non-root configured user'
assert_contains "${compose_file}" '^[[:space:]]+read_only:[[:space:]]+true$' 'game root filesystem must be read-only'
assert_contains "${compose_file}" '^[[:space:]]+cap_drop:[[:space:]]+\[ALL\]$' 'game must drop Linux capabilities'
assert_contains "${compose_file}" 'CONNECTION_TOKEN_SECRET_FILE:[[:space:]]+/run/secrets/connection_token_secret' 'game must consume a mounted Secret file'
assert_contains "${compose_file}" 'world-replay:/var/lib/festa-world' 'replay ledger must use a persistent volume'
assert_contains "${compose_file}" 'external:[[:space:]]+true' 'game must join the existing demo network'
pass 'Compose static security and persistence boundary'

if ! command -v docker >/dev/null 2>&1; then
  skip 'Docker CLI unavailable; Compose rendering deferred'
  exit 0
fi

secret_file="$(mktemp)"
rendered="$(mktemp)"
docker_config="$(mktemp -d)"
cleanup() {
  rm -f -- "${secret_file}" "${rendered}"
  rmdir -- "${docker_config}" 2>/dev/null || true
}
trap cleanup EXIT

printf 'local-contract-test-only-not-a-runtime-secret' | base64 >"${secret_file}"
export GAME_IMAGE_REF='registry.example.invalid/festa-world@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
export CONNECTION_TOKEN_SECRET_FILE="${secret_file}"
export DOCKER_CONFIG="${docker_config}"

docker compose -f "${compose_file}" config >"${rendered}"
assert_not_contains "${rendered}" '^[[:space:]]+ports:$' 'rendered Compose publishes a host port'
assert_contains "${rendered}" 'target:[[:space:]]+connection_token_secret' 'rendered Compose lost the Secret target'
assert_contains "${rendered}" 'source:[[:space:]]+world-replay' 'rendered Compose lost the replay volume'
pass 'Compose rendering'
