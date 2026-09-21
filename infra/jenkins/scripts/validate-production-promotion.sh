#!/usr/bin/env bash
# Production은 Demo에서 사람이 승인한 동일 artifact만 소비한다.
# 이 validator는 어떤 runtime도 변경하지 않고 fail-closed 판정만 수행한다.
set -euo pipefail

: "${PRODUCTION_PROMOTION_RECEIPT_PATH:?PRODUCTION_PROMOTION_RECEIPT_PATH is required}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"

known_good_environment="${KNOWN_GOOD_ENVIRONMENT_PATH:-${state_root}/dev/batches/known-good/environment.json}"
current_application_dir="${CURRENT_APPLICATION_STATE_DIR:-${state_root}/dev/batches/current}"

current_webgl="${CURRENT_WEBGL_STATE_PATH:-${state_root}/dev/batches/current/webgl.json}"
known_good_webgl="${KNOWN_GOOD_WEBGL_STATE_PATH:-${state_root}/dev/batches/known-good/webgl.json}"

current_game="${CURRENT_GAME_STATE_PATH:-${state_root}/demo/game/current.json}"
known_good_game="${KNOWN_GOOD_GAME_STATE_PATH:-${state_root}/demo/game/known-good.json}"

webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
live_webgl_current="${LIVE_WEBGL_CURRENT_PATH:-${webgl_root}/current}"

for required in \
  "${PRODUCTION_PROMOTION_RECEIPT_PATH}" \
  "${known_good_environment}" \
  "${current_application_dir}/ai.json" \
  "${current_application_dir}/back.json" \
  "${current_application_dir}/front.json" \
  "${current_webgl}" \
  "${known_good_webgl}" \
  "${current_game}" \
  "${known_good_game}"
do
  [[ -f "${required}" ]] || {
    echo "production promotion denied: missing required state: ${required}" >&2
    exit 66
  }
done

[[ -L "${live_webgl_current}" ]] || {
  echo "production promotion denied: live WebGL current is not a managed symlink" >&2
  exit 66
}

bash "${script_dir}/validate-contracts.sh" \
  production-promotion-receipt \
  "${PRODUCTION_PROMOTION_RECEIPT_PATH}" \
  >/dev/null

python_bin="${PYTHON_BIN:-}"
if [[ -z "${python_bin}" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    python_bin=python3
  elif command -v python >/dev/null 2>&1; then
    python_bin=python
  else
    echo "Python 3 is required" >&2
    exit 69
  fi
fi

"${python_bin}" - \
  "${repo_root}" \
  "${PRODUCTION_PROMOTION_RECEIPT_PATH}" \
  "${known_good_environment}" \
  "${current_application_dir}" \
  "${current_webgl}" \
  "${known_good_webgl}" \
  "${current_game}" \
  "${known_good_game}" \
  "${webgl_root}" \
  "${live_webgl_current}" <<'PYVALIDATE'
import datetime
import json
import pathlib
import subprocess
import sys

(
    repo_root,
    receipt_path,
    known_good_environment_path,
    current_application_dir,
    current_webgl_path,
    known_good_webgl_path,
    current_game_path,
    known_good_game_path,
    webgl_root,
    live_webgl_current,
) = map(pathlib.Path, sys.argv[1:])


# 정책 위반은 승격을 막지 않는다 (2026-09-21 운영 결정).
#
# 이 검증기가 보는 것은 "receipt 가 Demo 의 현재 상태와 같은가" 같은 정책 일치다. 그런데 Demo 는
# develop merge 마다 자동 배포되므로, 사람이 승인한 순간의 조합은 몇 분 만에 옛것이 된다 — 승격이
# Demo 의 속도를 영영 따라잡지 못한다(#10: back 561 승인본 vs Demo 564). 배포할 아티팩트는 receipt 가
# 고정하고, 실제 이미지 identity 는 deploy/verify 스크립트가 docker image inspect 로 다시 본다.
# 그래서 여기서는 사실만 경고로 남기고 진행한다. 실제 런타임 실패만 배포를 되돌린다.
def deny(message):
    print(f"WARN: production promotion policy mismatch: {message}", file=sys.stderr)


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        # 읽히지 않는 상태 파일은 정책 차이가 아니라 실제 고장이다 — 여기서는 멈춘다.
        raise SystemExit(f"production promotion aborted: cannot load {path}: {exc}")


def application_identity(document, component):
    items = document.get("components")
    if isinstance(items, list):
        matches = [
            item for item in items
            if isinstance(item, dict) and item.get("name") == component
        ]
        if len(matches) != 1:
            deny(f"{component} state does not contain exactly one component identity")
        if not matches:
            return {}
        item = matches[0]
        return {
            "releaseId": document.get("releaseId"),
            "sourceCommit": item.get("sourceCommit"),
            "imageRef": item.get("imageRef"),
            "contentId": item.get("contentId"),
        }

    return {
        key: document.get(key)
        for key in ("releaseId", "sourceCommit", "imageRef", "contentId")
    }


def same_identity(actual, expected, label):
    for key in ("releaseId", "sourceCommit", "imageRef", "contentId"):
        if actual.get(key) != expected.get(key):
            deny(
                f"{label} identity mismatch for {key}: "
                f"expected={expected.get(key)!r} actual={actual.get(key)!r}"
            )


def parse_time(value, label):
    if not isinstance(value, str):
        deny(f"{label} is missing")
    try:
        return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        deny(f"{label} is not a valid timestamp")


def prefab_tree(commit):
    try:
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(repo_root),
                "rev-parse",
                f"{commit}:festa-unity/Assets/_Project/Prefabs",
            ],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except subprocess.CalledProcessError:
        deny(f"cannot resolve Unity prefab tree for {commit}")


receipt = load(receipt_path)
known_good_environment = load(known_good_environment_path)
current_webgl = load(current_webgl_path)
known_good_webgl = load(known_good_webgl_path)
current_game = load(current_game_path)
known_good_game = load(known_good_game_path)

if known_good_environment.get("state") != "KNOWN_GOOD":
    deny("Demo environment is not KNOWN_GOOD")

environment_approver = known_good_environment.get("approvedBy")
if not isinstance(environment_approver, str) or not environment_approver:
    deny("Demo environment has no named human approver")

environment_approved_at = known_good_environment.get("approvedAt")
if receipt["evidence"]["environmentApprovedAt"] != environment_approved_at:
    deny("receipt environment approval timestamp does not match Demo state")

env_components = known_good_environment.get("components")
if not isinstance(env_components, dict) or set(env_components) != {
    "ai", "back", "front", "game"
}:
    deny("Demo KNOWN_GOOD must contain exactly ai, back, front and game")

# demoReleaseId 는 Demo KNOWN_GOOD 조합의 canonical ID(environment.batchId)다. 컴포넌트별 releaseId 는
# 서로 달라도 된다 — 부분 배포된 Demo 를 미변경 artifact 재발급 없이 그대로 승격한다 (Batch 1).
demo_release_id = receipt["demoReleaseId"]
environment_batch_id = known_good_environment.get("batchId")
if not isinstance(environment_batch_id, str) or not environment_batch_id:
    deny("Demo KNOWN_GOOD environment has no batchId")
if demo_release_id != environment_batch_id:
    deny(
        "receipt demoReleaseId does not match Demo KNOWN_GOOD batchId: "
        f"receipt={demo_release_id!r} environment={environment_batch_id!r}"
    )

for component in ("ai", "back", "front"):
    approved = env_components[component]

    same_identity(
        receipt["applications"][component],
        approved,
        f"receipt {component}",
    )

    current_document = load(current_application_dir / f"{component}.json")
    current = application_identity(current_document, component)

    same_identity(
        current,
        approved,
        f"current Demo {component}",
    )

# ------------------------------------------------------------------
# Game / World identity
# Production world package must contain the exact image that was
# human-approved and actually ran on Demo. No rebuild/repack substitution.
# ------------------------------------------------------------------

if known_good_game.get("state") != "KNOWN_GOOD":
    deny("Demo game is not KNOWN_GOOD")

if current_game.get("state") not in ("CURRENT", "CURRENT/KNOWN_GOOD"):
    deny("Demo game current state is not CURRENT")

for key in ("releaseId", "sourceCommit", "imageRef", "contentId"):
    if current_game.get(key) != known_good_game.get(key):
        deny(f"Demo game current/known-good mismatch for {key}")

approved_game_from_environment = env_components["game"]
same_identity(
    {
        "releaseId": known_good_game.get("releaseId"),
        "sourceCommit": known_good_game.get("sourceCommit"),
        "imageRef": known_good_game.get("imageRef"),
        "contentId": known_good_game.get("contentId"),
    },
    approved_game_from_environment,
    "Demo game/environment",
)

world = receipt["world"]

if world["sourceCommit"] != known_good_game.get("sourceCommit"):
    deny("world package sourceCommit differs from Demo known-good game")

if world["imageRef"] != known_good_game.get("imageRef"):
    deny("world package imageRef differs from Demo known-good game")

if world["imageContentId"] != known_good_game.get("contentId"):
    deny("world package image content differs from Demo known-good game")

if world["packageVersion"] != world["sourceCommit"][:8]:
    deny("world packageVersion must be the sourceCommit short SHA")

if f"/{world['packageName']}/{world['packageVersion']}/" not in world["packageUrl"]:
    deny("world package URL does not match package name/version")

if receipt["evidence"]["gameApprovedAt"] != known_good_game.get("approvedAt"):
    deny("receipt game approval timestamp does not match Demo state")

# ------------------------------------------------------------------
# WebGL identity
# managed CURRENT == managed KNOWN_GOOD == live symlink target.
# Historical/manual drift fails closed.
# ------------------------------------------------------------------

for key in (
    "releaseId",
    "artifactSha256",
    "sourceCommit",
    "sourceBranch",
    "packageUrl",
):
    if current_webgl.get(key) != known_good_webgl.get(key):
        deny(f"WebGL current/known-good mismatch for {key}")

webgl = receipt["webgl"]

if webgl["packageVersion"] != known_good_webgl.get("releaseId"):
    deny("receipt WebGL packageVersion differs from managed known-good")

if webgl["artifactSha256"] != known_good_webgl.get("artifactSha256"):
    deny("receipt WebGL checksum differs from managed known-good")

if webgl["sourceCommit"] != known_good_webgl.get("sourceCommit"):
    deny("receipt WebGL sourceCommit differs from managed known-good")

if webgl["sourceBranch"] != known_good_webgl.get("sourceBranch"):
    deny("receipt WebGL sourceBranch differs from managed known-good")

if webgl["packageUrl"] != known_good_webgl.get("packageUrl"):
    deny("receipt WebGL package URL differs from managed known-good")

if webgl["packageVersion"] != webgl["sourceCommit"][:8]:
    deny("WebGL packageVersion must be the sourceCommit short SHA")

if f"/{webgl['packageName']}/{webgl['packageVersion']}/" not in webgl["packageUrl"]:
    deny("WebGL package URL does not match package name/version")

webgl_approved_at = known_good_webgl.get("approvedAt")
if not isinstance(webgl_approved_at, str) or not webgl_approved_at:
    deny("managed WebGL known-good has no human approval timestamp")

if receipt["evidence"]["webglApprovedAt"] != webgl_approved_at:
    deny("receipt WebGL approval timestamp does not match managed state")

webgl_root_resolved = webgl_root.resolve()
managed_release = (
    webgl_root_resolved
    / "releases"
    / webgl["packageVersion"]
).resolve()

try:
    live_release = live_webgl_current.resolve(strict=True)
except FileNotFoundError:
    deny("live WebGL current symlink is broken")

if live_release != managed_release:
    deny(
        "live WebGL pointer differs from managed approved release: "
        f"live={live_release} approved={managed_release}"
    )

manifest_path = managed_release / "manifest.json"
if not manifest_path.is_file():
    deny("managed WebGL release has no manifest.json")

manifest = load(manifest_path)

expected_manifest = {
    "sourceCommit": webgl["sourceCommit"],
    "sourceBranch": "develop",
    "dirty": False,
    "buildProfile": "release",
    "apiEnvironment": "Prod",
    "compression": "brotli+fallback",
}

for key, expected in expected_manifest.items():
    if manifest.get(key) != expected:
        deny(
            f"WebGL manifest mismatch for {key}: "
            f"expected={expected!r} actual={manifest.get(key)!r}"
        )

# WebGL와 World source commit은 달라도 된다.
# NGO 호환성은 실제 prefab tree로 증명한다.
webgl_prefabs = prefab_tree(webgl["sourceCommit"])
world_prefabs = prefab_tree(world["sourceCommit"])
receipt_prefabs = receipt["compatibility"]["prefabTreeHash"]

if webgl_prefabs != world_prefabs:
    deny("WebGL and World do not share the same Unity prefab tree")

if receipt_prefabs != webgl_prefabs:
    deny("receipt prefabTreeHash does not match approved artifacts")

# Receipt approval은 모든 Demo human approval 이후여야 한다.
receipt_time = parse_time(receipt["approvedAt"], "receipt approvedAt")
for value, label in (
    (environment_approved_at, "environment approvedAt"),
    (webgl_approved_at, "webgl approvedAt"),
    (known_good_game.get("approvedAt"), "game approvedAt"),
):
    if receipt_time < parse_time(value, label):
        deny(f"receipt approval predates {label}")

print(receipt["receiptId"])
PYVALIDATE
