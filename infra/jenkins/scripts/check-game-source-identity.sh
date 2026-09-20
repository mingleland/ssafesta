#!/usr/bin/env bash
# canonical publish 직전 SCM 한 경로로 source 를 증명한다 (Batch 2):
#   commit 이 origin 에 존재하고, origin/develop 의 조상이며, zip manifest 가 dirty=false 이고, zip 과 image 가 같은 commit 을 가리킨다.
# 2026-09-20 외부 artifact 5f148b69 는 로컬에도 GitLab 에도 없는 commit 이었다 — 이런 산출물은 여기서 멈춘다.
set -euo pipefail
usage() { echo 'Usage: check-game-source-identity.sh --source-commit SHA [--webgl-zip ZIP] [--image-ref REF] [--remote origin] [--branch develop]' >&2; exit 64; }
source_commit='' webgl_zip='' image_ref='' remote='origin' branch='develop'
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-commit) source_commit="${2:-}"; shift 2 ;;
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

git -C "${repo}" fetch --quiet "${remote}" "${branch}" || { echo "cannot fetch ${remote}/${branch}" >&2; exit 69; }
git -C "${repo}" cat-file -e "${source_commit}^{commit}" 2>/dev/null \
  || { echo "SOURCE_NOT_IN_REPOSITORY: ${source_commit} is not a commit known to ${remote}" >&2; exit 65; }
git -C "${repo}" merge-base --is-ancestor "${source_commit}" "${remote}/${branch}" \
  || { echo "SOURCE_NOT_ON_BRANCH: ${source_commit} is not an ancestor of ${remote}/${branch}" >&2; exit 65; }
if [[ -n "${webgl_zip}" ]]; then
  bash "${script_dir}/validate-webgl-archive.sh" "${webgl_zip}" --source-commit "${source_commit}" --branch "${branch}" >/dev/null
fi
if [[ -n "${image_ref}" ]]; then
  label="$(${docker_bin} image inspect --format '{{index .Config.Labels "org.ssafy-festa.source-commit"}}' "${image_ref}" 2>/dev/null || true)"
  [[ "${label}" == "${source_commit}" ]] || { echo "IMAGE_SOURCE_MISMATCH: ${image_ref} label source-commit=${label:-missing} != ${source_commit}" >&2; exit 65; }
fi
echo "GAME_SOURCE_OK ${source_commit} ${remote}/${branch}"
