#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo 'Usage: validate-production-main-ancestry.sh <receipt.json> [target-commit]' >&2
  exit 64
}

[[ $# -ge 1 && $# -le 2 ]] || usage
receipt="$1"
target="${2:-HEAD}"
[[ -f "${receipt}" ]] || { echo "missing receipt: ${receipt}" >&2; exit 66; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
git -C "${repo_root}" cat-file -e "${target}^{commit}"

mapfile -t commits < <(
  python3 - "${receipt}" <<'PY'
import json, pathlib, sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
values=[doc['applications'][x]['sourceCommit'] for x in ('ai','back','front')]
values += [doc['webgl']['sourceCommit'], doc['world']['sourceCommit']]
for value in sorted(set(values)):
    print(value)
PY
)

(( ${#commits[@]} > 0 )) || { echo 'receipt contains no source commits' >&2; exit 65; }
for commit in "${commits[@]}"; do
  [[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || { echo "invalid source commit: ${commit}" >&2; exit 65; }
  git -C "${repo_root}" cat-file -e "${commit}^{commit}"
  git -C "${repo_root}" merge-base --is-ancestor "${commit}" "${target}" || {
    echo "Production ancestry denied: ${commit} is not an ancestor of ${target}" >&2
    exit 65
  }
  printf 'ANCESTOR=%s\n' "${commit}"
done

echo 'PRODUCTION_MAIN_ANCESTRY=PASS'
