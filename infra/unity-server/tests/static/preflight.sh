#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"

secret_file="$(mktemp)"
docker_config="$(mktemp -d)"
cleanup() {
  rm -f -- "${secret_file}"
  rmdir -- "${docker_config}" 2>/dev/null || true
}
trap cleanup EXIT

printf 'server-independent-preflight-test-material' | base64 >"${secret_file}"
export GAME_IMAGE_REF='registry.example.invalid/festa-world@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
export CONNECTION_TOKEN_SECRET_FILE="${secret_file}"
export DOCKER_CONFIG="${docker_config}"

bash "${unity_server_dir}/scripts/preflight.sh" --config-only

preflight="${unity_server_dir}/scripts/preflight.sh"
grep -Fq 'uname -m' "${preflight}" || { echo 'FAIL: host architecture check missing' >&2; exit 1; }
grep -Fq "image inspect --format '{{.Architecture}}'" "${preflight}" \
  || { echo 'FAIL: image architecture check missing' >&2; exit 1; }
grep -Fq 'DOCKER_DEFAULT_PLATFORM' "${preflight}" \
  || { echo 'FAIL: emulation platform guard missing' >&2; exit 1; }
