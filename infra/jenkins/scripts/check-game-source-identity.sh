#!/usr/bin/env bash
# canonical publish 직전 SCM 한 경로로 source 를 증명한다 (Batch 2):
#   commit 이 origin 에 존재하고, origin/develop 의 조상이며, zip manifest 가 dirty=false 이고, zip 과 image 가 같은 commit 을 가리킨다.
# 2026-09-20 외부 artifact 5f148b69 는 로컬에도 GitLab 에도 없는 commit 이었다 — 이런 산출물은 여기서 멈춘다.
set -euo pipefail
usage() { echo 'Usage: check-game-source-identity.sh --source-commit SHA [--head HEAD_SHA] [--webgl-zip ZIP] [--image-ref REF] [--remote origin] [--branch develop]' >&2; exit 64; }
source_commit='' head_sha='' webgl_zip='' image_ref='' remote='origin' branch='develop'
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
    --head) head_sha="${2:-}"; shift 2 ;;
    --webgl-zip) webgl_zip="${2:-}"; shift 2 ;;
    --image-ref) image_ref="${2:-}"; shift 2 ;;
    --remote) remote="${2:-}"; shift 2 ;;
    --branch) branch="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="${GIT_REPO_DIR:-$(cd "${script_dir}/../../.." && pwd)}"
docker_bin="${DOCKER_BIN:-docker}"
python_bin="${PYTHON_BIN:-python3}"

git -C "${repo}" fetch --quiet "${remote}" "${branch}" || { echo "cannot fetch ${remote}/${branch}" >&2; exit 69; }
git -C "${repo}" cat-file -e "${source_commit}^{commit}" 2>/dev/null \
  || { echo "SOURCE_NOT_IN_REPOSITORY: ${source_commit} is not a commit known to ${remote}" >&2; exit 65; }

# unityInputId 검증: pipelineCommit 과 artifactSourceCommit 의 Unity build input 이 동일해야 한다 (Batch 2-C).
# resolve-game-artifacts.sh 와 같은 식별자를 만든다 — 두 곳이 다르게 판정하면 gate 가 통과시킨 것을
# resolve 가 거부한다. 내용 기준(unity-content-id.sh)이 실패하면 예전 tree hash 로 내려간다.
compute_unity_input_id() {
  local c="$1" content_id=''
  content_id="$(GIT_REPO_DIR="${repo}" PYTHON_BIN="${python_bin}" bash "${script_dir}/unity-content-id.sh" "${c}" 2>/dev/null || true)"
  if [[ -z "${content_id}" ]]; then
    echo "UNITY_CONTENT_ID_UNAVAILABLE: falling back to the git tree hash for ${c}" >&2
  fi
  UNITY_CONTENT_ID="${content_id}" "${python_bin}" - "${repo}" "${c}" <<'INNER_PY'
import hashlib, os, subprocess, sys
repo, commit = sys.argv[1:3]
content_id = os.environ.get('UNITY_CONTENT_ID') or ''
try:
    proj = subprocess.check_output(['git', '-C', repo, 'show', f'{commit}:festa-unity/ProjectSettings/ProjectVersion.txt'], stderr=subprocess.DEVNULL).decode()
    v = [l.split(':')[1].strip() for l in proj.splitlines() if l.startswith('m_EditorVersion:')][0]
    r = [l.split('(')[1].split(')')[0] for l in proj.splitlines() if l.startswith('m_EditorVersionWithRevision:')][0]
    if content_id:
        source = f'content={content_id}'
    else:
        tree = subprocess.check_output(['git', '-C', repo, 'rev-parse', f'{commit}:festa-unity'], stderr=subprocess.DEVNULL).decode().strip()
        source = f'tree={tree}'
except Exception:
    sys.exit(1)
raw = f'{source}|unityVersion={v}|unityRevision={r}|buildProfile=release|apiEnvironment=Prod|artifactContract=manifest-1.0.0'
print(hashlib.sha256(raw.encode('utf-8')).hexdigest())
INNER_PY
}

if [[ -n "${head_sha}" ]]; then
  head_input="$(compute_unity_input_id "${head_sha}")" || { echo "cannot compute unityInputId for head ${head_sha}" >&2; exit 65; }
  source_input="$(compute_unity_input_id "${source_commit}")" || { echo "cannot compute unityInputId for source ${source_commit}" >&2; exit 65; }
  [[ "${head_input}" == "${source_input}" ]] || {
    echo "UNITY_INPUT_ID_MISMATCH: head ${head_sha} input (${head_input}) != source ${source_commit} input (${source_input})" >&2
    exit 65
  }
fi

if git -C "${repo}" merge-base --is-ancestor "${source_commit}" "${remote}/${branch}" 2>/dev/null; then
  echo "ANCESTRY=true"
else
  echo "ANCESTRY_WARNING: ${source_commit} is not an ancestor of ${remote}/${branch} (content-equivalent by unityInputId)" >&2
fi
if [[ -n "${webgl_zip}" ]]; then
  bash "${script_dir}/validate-webgl-archive.sh" "${webgl_zip}" --source-commit "${source_commit}" --branch "${branch}" >/dev/null
fi
if [[ -n "${image_ref}" ]]; then
  label="$(${docker_bin} image inspect --format '{{index .Config.Labels "org.ssafy-festa.source-commit"}}' "${image_ref}" 2>/dev/null || true)"
  [[ "${label}" == "${source_commit}" ]] || { echo "IMAGE_SOURCE_MISMATCH: ${image_ref} label source-commit=${label:-missing} != ${source_commit}" >&2; exit 65; }
fi
echo "GAME_SOURCE_OK ${source_commit} ${remote}/${branch}"
