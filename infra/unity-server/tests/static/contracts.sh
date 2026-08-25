#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
repo_root="$(cd "${unity_server_dir}/../.." && pwd)"

source "${unity_server_dir}/tests/lib/assert.sh"

openapi="${repo_root}/specs/infra-003-unity-server-deploy/contracts/world-session.openapi.yaml"
token_contract="${repo_root}/specs/infra-003-unity-server-deploy/contracts/world-entry-token.md"
runtime_contract="${unity_server_dir}/contracts/runtime-env.md"
env_example="${unity_server_dir}/.env.example"

assert_file "${openapi}"
assert_file "${token_contract}"
assert_file "${runtime_contract}"
assert_file "${env_example}"

assert_contains "${openapi}" '^openapi:[[:space:]]+3\.1\.0$' 'OpenAPI version must remain 3.1.0'
assert_contains "${openapi}" '^[[:space:]]+required:[[:space:]]+false$' 'request body must remain optional'
assert_contains "${openapi}" '^[[:space:]]+worldId:$' 'request body is missing worldId'
assert_contains "${openapi}" '^[[:space:]]+const:[[:space:]]+11F$' 'worldId must be fixed to 11F'
assert_contains "${openapi}" "^[[:space:]]+'400':$" 'unsupported worldId must document HTTP 400'
assert_contains "${openapi}" 'const:[[:space:]]+VALIDATION_FAILED' 'HTTP 400 must use VALIDATION_FAILED'
assert_contains "${openapi}" 'field:[[:space:]]+\{[[:space:]]*type:[[:space:]]+string' 'validation details must identify the field'
assert_contains "${openapi}" 'worldId:[[:space:]]+\{[[:space:]]*const:[[:space:]]+11F[[:space:]]*\}' 'response worldId must remain 11F'
assert_contains "${openapi}" 'channelId:[[:space:]]+\{[[:space:]]*const:[[:space:]]+11F-01[[:space:]]*\}' 'response channelId must remain 11F-01'
pass 'world-session request/response and validation contract'

for expected in 'HS256' 'decoded length at least 32 bytes' 'TTL: 120 seconds' \
  'ssafesta-backend' 'ssafesta-world' 'worldId=11F' 'channelId=11F-01'; do
  grep -Fq -- "${expected}" "${token_contract}" || fail "token contract missing: ${expected}"
done
pass 'world-entry-token fixed claims and cryptographic boundary'

for key in GAME_IMAGE_REF ROOT_DOMAIN CONNECTION_TOKEN_SECRET_FILE WORLD_ENTRY_TOKEN_ISSUER \
  WORLD_ENTRY_TOKEN_AUDIENCE WORLD_ID WORLD_CHANNEL_ID WORLD_MAX_PLAYERS WORLD_LEDGER_PATH; do
  grep -Eq "^${key}=" "${env_example}" || fail ".env.example missing ${key}"
done
assert_not_contains "${env_example}" '^(CONNECTION_TOKEN_SECRET|CONNECTION_TOKEN_SECRET_FILE)=.+' 'Secret values must not be committed'
pass 'runtime environment reference names'

assert_contains "${runtime_contract}" '동일 Secret reference' 'Backend and game must consume one Secret reference'
assert_contains "${runtime_contract}" '`120s`' 'runtime mapping must preserve the 120 second TTL'
pass 'cross-component runtime mapping'
