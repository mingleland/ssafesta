#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
script="${repo_root}/infra/jenkins/scripts/freshness.sh"
old=0123456789abcdef0123456789abcdef01234567
new=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
FRESHNESS_EXPECTED_SHA="${new}" FRESHNESS_ACTUAL_SHA="${new}" bash "${script}" | grep -q 'CURRENT'
set +e
out="$(FRESHNESS_EXPECTED_SHA="${old}" FRESHNESS_ACTUAL_SHA="${new}" bash "${script}" 2>&1)"; code=$?
set -e
[[ ${code} -eq 75 ]] && grep -q 'SUPERSEDED' <<<"${out}"
echo "PASS: deploy freshness"
