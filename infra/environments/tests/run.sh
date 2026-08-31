#!/usr/bin/env bash
# infra-002 로컬 계약·통합·보안·장애·자원 검사를 한 진입점에서 실행한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
groups=(contract integration security failure resource)

for group in "${groups[@]}"; do
  group_dir="${script_dir}/${group}"
  [[ -d "${group_dir}" ]] || continue
  printf '\n[%s]\n' "${group}"
  if [[ -x "${group_dir}/run.sh" || -f "${group_dir}/run.sh" ]]; then
    bash "${group_dir}/run.sh"
    continue
  fi
  while IFS= read -r test_file; do
    printf '==> %s\n' "${test_file##*/}"
    bash "${test_file}"
  done < <(find "${group_dir}" -maxdepth 1 -type f -name '*.sh' | sort)
done
