#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT; mkdir -p "${work}/state/candidates"
candidate_id="sha256:$(printf 'b%.0s' {1..64})"
cat >"${work}/state/candidates/release.json" <<JSON
{"components":[{"name":"ai","imageRef":"festa-ai:candidate","contentId":"${candidate_id}"}]}
JSON
cat >"${work}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == 'image ls' ]]; then
  printf 'festa-ai:new|new|2026-08-20\nfesta-ai:candidate|candidate|2026-08-19\nfesta-ai:stale|stale|2026-08-18\n'
elif [[ "$1 $2" == 'image inspect' ]]; then
  case "${@: -1}" in
    festa-ai:new) printf 'sha256:%064d\n' 1;;
    festa-ai:candidate) printf 'sha256:%s\n' "$(printf 'b%.0s' {1..64})";;
    festa-ai:stale) printf 'sha256:%064d\n' 3;;
  esac
elif [[ "$1 $2" == 'image rm' ]]; then echo "REMOVED $3" >>"${FAKE_DOCKER_LOG}"
else echo "unexpected docker args: $*" >&2; exit 64
fi
SH
chmod +x "${work}/docker"; export FAKE_DOCKER_LOG="${work}/docker.log"
STATE_ROOT="${work}/state" DOCKER_BIN="${work}/docker" IMAGE_RETENTION_PER_COMPONENT=1 \
  bash "${repo_root}/infra/deploy/scripts/prune-images.sh" >"${work}/dry-run.txt"
grep -q 'PROTECTED festa-ai:new' "${work}/dry-run.txt"
grep -q 'PROTECTED festa-ai:candidate' "${work}/dry-run.txt"
grep -q 'DRY_RUN_REMOVE festa-ai:stale' "${work}/dry-run.txt"
[[ ! -e "${FAKE_DOCKER_LOG}" ]]
STATE_ROOT="${work}/state" DOCKER_BIN="${work}/docker" IMAGE_RETENTION_PER_COMPONENT=1 PRUNE_APPROVED=YES \
  bash "${repo_root}/infra/deploy/scripts/prune-images.sh" >/dev/null
grep -q 'REMOVED sha256:' "${FAKE_DOCKER_LOG}"
echo 'PASS: image pruning protects newest, current/known-good/candidate references and defaults to dry-run'
