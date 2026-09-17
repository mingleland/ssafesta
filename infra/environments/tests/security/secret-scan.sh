#!/usr/bin/env bash
# T064: Verifies the shared scanner rejects leaked secrets without echoing their values.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

scanner="${repo_root}/infra/jenkins/scripts/secret-scan.sh"
fixture_test="${repo_root}/infra/tests/security/test-secret-leak.sh"

assert_file "${scanner}"
assert_file "${fixture_test}"

# The production scanner is shared by GitLab, Jenkins, and this environment gate.
(cd "${repo_root}" && bash "${scanner}" --tracked --path infra/environments --path infra/jenkins)
bash "${fixture_test}"

pass 'environment config and CI inputs are clean; synthetic log and credential leaks are rejected'
