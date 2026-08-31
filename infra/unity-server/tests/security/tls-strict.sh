#!/usr/bin/env bash
# 외부 TLS 검증기가 인증서 체인과 world host 이름 검사를 우회하지 못하게 한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

verifier="${unity_server_dir}/scripts/verify-public-wss.sh"
assert_file "${verifier}"
assert_contains "${verifier}" 'openssl[[:space:]]+s_client' 'TLS verifier must use openssl s_client'
assert_contains "${verifier}" '-verify_return_error' 'TLS verifier must fail on an invalid chain'
assert_contains "${verifier}" '-verify_hostname[[:space:]]+"?\$\{world_host\}"?' 'TLS verifier must verify the world hostname'
assert_not_contains "${verifier}" '(^|[[:space:]])-k([[:space:]]|$)' 'TLS verification must not use curl -k'
assert_not_contains "${verifier}" '-verify[[:space:]]+0' 'TLS verification must not be disabled'
pass 'strict origin TLS verification contract'
