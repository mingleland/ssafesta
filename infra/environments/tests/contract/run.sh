#!/usr/bin/env bash
# infra-002 공급자 중립 계약 테스트를 정해진 순서로 실행한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

found=0
while IFS= read -r test_file; do
  found=1
  printf '==> %s\n' "${test_file##*/}"
  bash "${test_file}"
done < <(find "${script_dir}" -maxdepth 1 -type f -name '*.sh' ! -name 'run.sh' | sort)

if [[ ${found} -eq 0 ]]; then
  printf 'No contract tests registered yet.\n'
fi
