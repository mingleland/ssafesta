#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/.." && pwd)"
source "${script_dir}/lib/assert.sh"

while IFS= read -r -d '' script; do
  bash -n "${script}"
  grep -q '^set -euo pipefail$' "${script}" || fail "strict mode missing: ${script}"
done < <(find "${unity_server_dir}" -type f -name '*.sh' -print0)
pass 'shell syntax and strict mode'

bash "${script_dir}/static/contracts.sh"
bash "${script_dir}/static/preflight.sh"
bash "${script_dir}/integration/idle-timeout.sh"
bash "${script_dir}/integration/game-compose.sh"
bash "${script_dir}/integration/public-wss.sh"
bash "${script_dir}/security/tls-strict.sh"
bash "${script_dir}/security/secret-scan.sh"

echo 'infra-003 server-independent static checks completed.'
