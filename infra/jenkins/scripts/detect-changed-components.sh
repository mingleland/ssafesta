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
}
component_config = {
    "infra/environments/compose/dev/ai.yaml": "ai",
    "infra/environments/compose/dev/back.yaml": "back",
    "infra/environments/compose/dev/front.yaml": "front",
    "infra/environments/compose/dev/game.yaml": "game",
    "infra/deploy/compose/dev/ai.compose.yaml": "ai",
    "infra/deploy/compose/dev/back.compose.yaml": "back",
    "infra/deploy/compose/dev/front.compose.yaml": "front",
    "infra/deploy/compose/dev/game.compose.yaml": "game",
}
shared_exact = {
    "Jenkinsfile",
    ".gitlab-ci.yml",
    "infra/environments/compose/dev/base.yaml",
    "infra/environments/config/manifests/dev.json",
    "infra/versions.env",
}
shared_prefixes = (
    "ci/",
    "infra/jenkins/",
    "infra/deploy/scripts/",
    "infra/environments/scripts/",
    "infra/tests/",
)
docs_prefixes = ("docs/", "specs/", "infra/evidence/")

components = set()
deploy_components = set()
reasons = set()
unknown = []
shared = False
for path in paths:
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
    elif path in shared_exact or path.startswith(shared_prefixes):
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
if not paths:
    reasons.add("no-changes")

print(json.dumps({
    "version": 1,
    "eventKind": "jenkins_develop_push",
    "baseSha": base,
    "headSha": head,
    "components": [component for component in order if component in components],
    "deployComponents": [component for component in order if component in deploy_components],
    "reasons": sorted(reasons),
    "paths": paths,
}, separators=(",", ":")))
PY
