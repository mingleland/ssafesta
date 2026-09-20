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
apps = {"ai", "back", "front"}
source_roots = {
    "festa-ai/": "ai",
    "backend/": "back",
    "festa-frontend/": "front",
    "festa-unity/": "game",
    # Dedicated Server 의 배포 경로다. 게임 하나만 고르고 ai·back·front 를 끌고 오지 않는다 —
    # deploy-game.sh / compose / nginx 가 바뀌면 재배포로 검증되어야 하고, 그것이 game 선택이다.
    "infra/unity-server/": "game",
}
# develop 의 실제 통합 배포 대상은 demo 다. demo component overlay 변경은 그 component 만 재빌드·재배포한다.
component_config = {
    "infra/environments/compose/demo/ai.yaml": "ai",
    "infra/environments/compose/demo/back.yaml": "back",
    "infra/environments/compose/demo/front.yaml": "front",
}

# 네 축을 분리한다 (Batch 1, T-168 후속 — 2026-09-20 실측: develop 빌드 10건 중 8건이 shared-ci 로 배포 0).
#   validationComponents  검증 범위. shared 변경이면 네 컴포넌트 전부.
#   buildComponents       실제로 이미지를 만드는 컴포넌트. game 은 gameBuildRequired 일 때만.
#   deployComponents      demo 를 갱신하는 컴포넌트. 변경된 app + (runtime-shared 면 app 3종) + (game 변경이면 game).
#   sharedCiChanged       CI/orchestration 또는 runtime 공용 입력이 바뀌었다는 신호. **배포를 취소하는 신호가 아니다.**
#
# runtime_shared: demo 런타임 계약 자체라 app 3종을 다시 만들고 다시 배포해야 한다.
runtime_shared_exact = {
    "infra/environments/compose/demo/base.yaml",
    "infra/environments/config/manifests/demo.json",
    "infra/environments/scripts/deploy-environment.sh",
    "infra/environments/scripts/verify-environment.sh",
    "infra/environments/scripts/preflight.sh",
    "infra/environments/scripts/validate-json-schema.sh",
    "infra/deploy/scripts/package-local-image.sh",
    "infra/versions.env",
    # 도커 빌드 컨텍스트 제외 규칙이라 이미지 내용에 영향을 줄 수 있다.
    ".dockerignore",
}
# ci_only_shared: 검증 범위만 넓힌다. 이것만으로는 이미지를 만들지도, Unity 를 돌리지도, 배포하지도 않는다.
ci_only_shared_exact = {
    "Jenkinsfile",
    ".gitlab-ci.yml",
    # LFS 추적 규칙 — 검증 범위를 넓히고(아래 game_build_exact 로) Unity 후보를 다시 만든다 (Batch 2).
    ".gitattributes",
    "infra/.env.example",
    "infra/environments/config/environments/demo.env.example",
}
ci_only_shared_prefixes = (
    "ci/",
    "infra/jenkins/",
    "infra/deploy/scripts/",
    "infra/deploy/compose/production/",
    "infra/deploy/compose/integration/",
    "infra/deploy/state/",
    "infra/environments/scripts/",
    "infra/environments/nginx/",
    "infra/environments/postgres/",
    "infra/environments/redis/",
    "infra/environments/storage/",
    "infra/environments/compose/data/",
    "infra/environments/config/",
)
# 알려지지 않은 demo overlay 는 fail-closed. (dev 전용 config 는 아래 validation_only 로 강등한다.)
strict_config_prefixes = (
    "infra/environments/compose/demo/",
)
# 빌드 산출물에 들어가지 않는 경로. source_roots 보다 먼저 판정해 runbook 한 줄 수정이 Unity 빌드를 끌고 오지 않게 한다.
docs_prefixes = (
    "docs/",
    "specs/",
    "infra/evidence/",
    "infra/unity-server/runbooks/",
    "infra/deploy/runbooks/",
    "spikes/",
    ".claude/",
)
docs_exact = {
    ".gitignore",
}
# 배포 기계 자체를 검증하는 파일과 legacy dev 환경 전용 설정. 컴포넌트를 고르지 않는다.
# (dev.json/game.compose.yaml 등 game_build_exact 에 있는 항목은 아래 gameBuildRequired 계산에는 그대로 참여한다.)
validation_only_prefixes = (
    "infra/unity-server/tests/",
    "infra/tests/",
    "infra/environments/tests/",
    "infra/environments/compose/dev/",
    "infra/deploy/compose/dev/",
)
validation_only_exact = {
    "infra/environments/config/manifests/dev.json",
    "infra/environments/config/environments/dev.env.example",
}

# Unity 후보는 그 후보를 실제로 바꿀 수 있는 입력이 바뀔 때만 만든다 (입력 집합은 Batch 1 에서 변경하지 않았다).
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
    # LFS 규칙은 checkout 에 실제로 들어오는 binary 를 바꾼다 (Batch 2).
    ".gitattributes",
    "infra/jenkins/pipelines/component.groovy",
    "infra/jenkins/scripts/with-credentials.sh",
    "infra/jenkins/scripts/transfer-local-images.sh",
    "infra/deploy/scripts/package-local-image.sh",
    "infra/deploy/compose/dev/game.compose.yaml",
    "infra/environments/compose/dev/game.yaml",
}

touched = set()
reasons = set()
unknown = []
ci_shared = False
runtime_shared = False
for path in paths:
    if path in validation_only_exact or path.startswith(validation_only_prefixes):
        reasons.add("validation-only")
        continue
    if path in docs_exact or path.startswith(docs_prefixes):
        reasons.add("docs-only")
        continue
    component = next((value for prefix, value in source_roots.items() if path.startswith(prefix)), None)
    if component:
        touched.add(component)
        reasons.add("component-source")
    elif path in component_config:
        touched.add(component_config[path])
        reasons.add("component-deploy-config")
    elif path in runtime_shared_exact:
        runtime_shared = True
        reasons.add("runtime-shared")
    elif path in ci_only_shared_exact:
        ci_shared = True
        reasons.add("shared-ci")
    elif path.startswith(strict_config_prefixes):
        unknown.append(path)
    elif path.startswith(ci_only_shared_prefixes):
        ci_shared = True
        reasons.add("shared-ci")
    elif path.endswith(".md"):
        reasons.add("docs-only")
    else:
        unknown.append(path)

if unknown:
    raise SystemExit("unclassified path(s); refusing build/deploy: " + ", ".join(unknown))

game_build_required = any(
    path in game_build_exact
    or path.startswith(game_build_prefixes)
    or (
        path.startswith(game_runtime_prefixes)
        and not path.startswith(validation_only_prefixes)
    )
    for path in paths
)

shared = ci_shared or runtime_shared
validation = set(order) if shared else set(touched)
build = (touched & apps) | (apps if runtime_shared else set()) | ({"game"} if game_build_required else set())
deploy = (touched & apps) | (apps if runtime_shared else set()) | ({"game"} if ("game" in touched and game_build_required) else set())

if not paths:
    reasons.add("no-changes")

ordered = lambda selected: [component for component in order if component in selected]
print(json.dumps({
    "version": 2,
    "eventKind": "jenkins_develop_push",
    "baseSha": base,
    "headSha": head,
    # 하위 호환: components == validationComponents.
    "components": ordered(validation),
    "validationComponents": ordered(validation),
    "buildComponents": ordered(build),
    "deployComponents": ordered(deploy),
    "sharedCiChanged": shared,
    "runtimeSharedChanged": runtime_shared,
    "gameBuildRequired": game_build_required,
    "reasons": sorted(reasons),
    "paths": paths,
}, separators=(",", ":")))
PY
