#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 --base <full-sha> --head <full-sha> [--branch develop]" >&2
  exit 64
}

base="${GIT_BEFORE_SHA:-}"
head="${GIT_COMMIT:-}"
branch="${BRANCH_NAME:-${GIT_BRANCH:-}}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="${2:-}"; shift 2 ;;
    --head) head="${2:-}"; shift 2 ;;
    --branch) branch="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

branch="${branch#origin/}"
[[ "${branch}" == develop ]] || { echo "only develop pushes are supported" >&2; exit 64; }
[[ "${head}" =~ ^[0-9a-f]{40}$ ]] || { echo "head must be a full lowercase SHA" >&2; exit 64; }

if [[ -z "${base}" || "${base}" == "${head}" ]]; then
  base="$(git rev-parse "${head}^1" 2>/dev/null || true)"
  [[ -n "${base}" ]] || { echo "base SHA is required for a root commit" >&2; exit 64; }
fi
[[ "${base}" =~ ^[0-9a-f]{40}$ ]] || { echo "base must be a full lowercase SHA" >&2; exit 64; }
git cat-file -e "${base}^{commit}" 2>/dev/null || { echo "base commit is unavailable locally" >&2; exit 65; }
git cat-file -e "${head}^{commit}" 2>/dev/null || { echo "head commit is unavailable locally" >&2; exit 65; }
git merge-base --is-ancestor "${base}" "${head}" || { echo "base must be an ancestor of head" >&2; exit 65; }

paths_file="$(mktemp)"
trap 'rm -f "${paths_file}"' EXIT
git diff --name-only -z "${base}..${head}" > "${paths_file}"

python3 - "${paths_file}" "${base}" "${head}" <<'PY'
import json
import pathlib
import sys

paths = [path.decode("utf-8") for path in pathlib.Path(sys.argv[1]).read_bytes().split(b"\0") if path]
base, head = sys.argv[2:]
order = ("ai", "back", "front", "game")
source_roots = {
    "festa-ai/": "ai",
    "backend/": "back",
    "festa-frontend/": "front",
    "festa-unity/": "game",
    # Dedicated Server 의 배포 경로다. 게임 하나만 고르고 ai·back·front 를 끌고 오지 않는다 —
    # deploy-game.sh / compose / nginx 가 바뀌면 재배포로 검증되어야 하고, 그것이 game 선택이다.
    "infra/unity-server/": "game",
}
component_config = {
    "infra/environments/compose/dev/ai.yaml": "ai",
    "infra/environments/compose/dev/back.yaml": "back",
    "infra/environments/compose/dev/front.yaml": "front",
    "infra/environments/compose/dev/game.yaml": "game",
    # develop의 실제 통합 배포 대상은 demo다. demo component overlay 변경은
    # shared-ci가 아니라 해당 component만 재빌드/재배포해야 한다.
    "infra/environments/compose/demo/ai.yaml": "ai",
    "infra/environments/compose/demo/back.yaml": "back",
    "infra/environments/compose/demo/front.yaml": "front",
    "infra/deploy/compose/dev/ai.compose.yaml": "ai",
    "infra/deploy/compose/dev/back.compose.yaml": "back",
    "infra/deploy/compose/dev/front.compose.yaml": "front",
    "infra/deploy/compose/dev/game.compose.yaml": "game",
}
shared_exact = {
    "Jenkinsfile",
    ".gitlab-ci.yml",
    # 도커 빌드 컨텍스트 제외 규칙이라 이미지 내용에 영향을 줄 수 있다. 문서 취급하면 검증 없이
    # 통과하므로 전체 재빌드로 둔다.
    ".dockerignore",
    "infra/environments/compose/dev/base.yaml",
    "infra/environments/compose/demo/base.yaml",
    "infra/environments/config/manifests/dev.json",
    "infra/environments/config/environments/dev.env.example",
    "infra/.env.example",
    "infra/versions.env",
}
strict_config_prefixes = (
    "infra/environments/compose/dev/",
    "infra/environments/compose/demo/",
    "infra/deploy/compose/dev/",
)

shared_prefixes = (
    "ci/",
    "infra/jenkins/",
    # 아래 둘은 전 컴포넌트의 런타임 설정이라 전체 재빌드가 맞다. 하위의 dev component compose
    # 파일은 component_config 가 먼저 잡으므로 여기 포함돼도 component 선택이 유지된다.
    "infra/deploy/",
    "infra/environments/",
    "infra/tests/",
)
# 빌드 산출물에 들어가지 않는 경로. 아래 판정을 source_roots 보다 먼저 두어 runbook 한 줄 수정이
# Unity 빌드를 끌고 오지 않게 한다. 반대로 festa-*/README.md 는 여기 없으므로 그대로 component
# 변경으로 잡힌다 — 긴급 재빌드 수단(INFRA-T-097)을 남겨 둔다.
docs_prefixes = (
    "docs/",
    "specs/",
    "infra/evidence/",
    "infra/unity-server/runbooks/",
    "spikes/",
    ".claude/",
)
docs_exact = {
    ".gitignore",
    ".gitattributes",
}

# These files validate the deployment machinery itself. Changing a test must
# never be interpreted as changing the Dedicated Server runtime artifact.
validation_only_prefixes = (
    "infra/unity-server/tests/",
)

# Shared CI changes still select all logical components, but Unity is much more
# expensive than the app CI and requires an activated editor. Build a game
# candidate only when an input that can actually change that candidate changed.
game_build_prefixes = (
    "festa-unity/",
    "ci/",
    "infra/jenkins/agents/",
)
game_runtime_prefixes = (
    "infra/unity-server/",
)
game_build_exact = {
    "infra/versions.env",
    "infra/jenkins/pipelines/component.groovy",
    "infra/jenkins/scripts/with-credentials.sh",
    "infra/jenkins/scripts/transfer-local-images.sh",
    "infra/deploy/scripts/package-local-image.sh",
    "infra/deploy/compose/dev/game.compose.yaml",
    "infra/environments/compose/dev/game.yaml",
}

components = set()
deploy_components = set()
reasons = set()
unknown = []
shared = False
for path in paths:
    if path.startswith(validation_only_prefixes):
        reasons.add("validation-only")
        continue
    if path in docs_exact or path.startswith(docs_prefixes):
        reasons.add("docs-only")
        continue
    component = next((value for prefix, value in source_roots.items() if path.startswith(prefix)), None)
    if component:
        components.add(component)
        deploy_components.add(component)
        reasons.add("component-source")
    elif path in component_config:
        component = component_config[path]
        components.add(component)
        deploy_components.add(component)
        reasons.add("component-deploy-config")
    elif path in shared_exact:
        shared = True
        reasons.add("shared-ci")
    elif path.startswith(strict_config_prefixes):
        unknown.append(path)
    elif path.startswith(shared_prefixes):
        shared = True
        reasons.add("shared-ci")
    elif path.startswith(docs_prefixes) or path.endswith(".md"):
        reasons.add("docs-only")
    else:
        unknown.append(path)

if unknown:
    raise SystemExit("unclassified path(s); refusing build/deploy: " + ", ".join(unknown))
if shared:
    components = set(order)
    deploy_components.clear()

game_build_required = any(
    path in game_build_exact
    or path.startswith(game_build_prefixes)
    or (
        path.startswith(game_runtime_prefixes)
        and not path.startswith(validation_only_prefixes)
    )
    for path in paths
)

if not paths:
    reasons.add("no-changes")

print(json.dumps({
    "version": 1,
    "eventKind": "jenkins_develop_push",
    "baseSha": base,
    "headSha": head,
    "components": [component for component in order if component in components],
    "deployComponents": [component for component in order if component in deploy_components],
    "gameBuildRequired": game_build_required,
    "reasons": sorted(reasons),
    "paths": paths,
}, separators=(",", ":")))
PY
