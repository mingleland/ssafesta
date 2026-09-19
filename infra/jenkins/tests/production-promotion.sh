#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
validator="${repo_root}/infra/jenkins/scripts/validate-production-promotion.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

commit="$(git -C "${repo_root}" rev-parse HEAD)"
short="${commit:0:8}"
prefab_tree="$(
  git -C "${repo_root}" rev-parse \
    "${commit}:festa-unity/Assets/_Project/Prefabs"
)"

state="${work}/state"
webgl_root="${work}/webgl"

mkdir -p \
  "${state}/dev/batches/current" \
  "${state}/dev/batches/known-good" \
  "${state}/demo/game" \
  "${webgl_root}/releases/${short}"

COMMIT="${commit}" \
SHORT="${short}" \
PREFAB_TREE="${prefab_tree}" \
WORK="${work}" \
STATE="${state}" \
WEBGL_ROOT="${webgl_root}" \
python3 <<'PYFIXTURE'
import json
import os
import pathlib

commit = os.environ["COMMIT"]
short = os.environ["SHORT"]
prefab = os.environ["PREFAB_TREE"]
work = pathlib.Path(os.environ["WORK"])
state = pathlib.Path(os.environ["STATE"])
webgl_root = pathlib.Path(os.environ["WEBGL_ROOT"])

release_id = f"develop-{commit}-1"

ids = {
    "ai": "sha256:" + "a" * 64,
    "back": "sha256:" + "b" * 64,
    "front": "sha256:" + "c" * 64,
    "game": "sha256:" + "d" * 64,
}

components = {
    name: {
        "releaseId": release_id,
        "sourceCommit": commit,
        "imageRef": f"festa-{name}:{commit}",
        "contentId": ids[name],
    }
    for name in ("ai", "back", "front", "game")
}

known_good_environment = {
    "schemaVersion": "1.0.0",
    "state": "KNOWN_GOOD",
    "approvedAt": "2026-09-19T00:01:00Z",
    "approvedBy": "fixture-approver",
    "approvedFromHistory": release_id,
    "components": components,
}

(state / "dev/batches/known-good/environment.json").write_text(
    json.dumps(known_good_environment, indent=2) + "\n"
)

release_manifest = {
    "schemaVersion": "1.0.0",
    "releaseId": release_id,
    "components": [
        {
            "name": name,
            "sourceCommit": commit,
            "imageRef": f"festa-{name}:{commit}",
            "contentId": ids[name],
        }
        for name in ("ai", "back", "front", "game")
    ],
}

for name in ("ai", "back", "front"):
    (state / f"dev/batches/current/{name}.json").write_text(
        json.dumps(release_manifest, indent=2) + "\n"
    )

game_current = {
    "schemaVersion": "1.0.0",
    "targetId": "demo/game",
    "releaseId": release_id,
    "sourceCommit": commit,
    "imageRef": f"festa-game:{commit}",
    "contentId": ids["game"],
    "state": "CURRENT",
    "startedAt": "2026-09-19T00:00:00Z",
    "promotedAt": "2026-09-19T00:00:30Z",
}

game_known_good = dict(game_current)
game_known_good["state"] = "KNOWN_GOOD"
game_known_good["approvedAt"] = "2026-09-19T00:03:00Z"
game_known_good["approvedFromCurrent"] = release_id

(state / "demo/game/current.json").write_text(
    json.dumps(game_current, indent=2) + "\n"
)

(state / "demo/game/known-good.json").write_text(
    json.dumps(game_known_good, indent=2) + "\n"
)

webgl_package_url = (
    "https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/"
    f"festa-webgl/{short}/festa-webgl-release-{short}.zip"
)

webgl_current = {
    "schemaVersion": "1.0.0",
    "releaseId": short,
    "artifactSha256": "e" * 64,
    "sourceCommit": commit,
    "sourceBranch": "develop",
    "packageUrl": webgl_package_url,
    "publicBaseUrl": "https://demo.example.invalid/unity",
    "verifiedVia": "edge",
    "recordedAt": "2026-09-19T00:00:45Z",
}

webgl_known_good = dict(webgl_current)
webgl_known_good["approvedAt"] = "2026-09-19T00:02:00Z"
webgl_known_good["approvedFromCurrent"] = short

(state / "dev/batches/current/webgl.json").write_text(
    json.dumps(webgl_current, indent=2) + "\n"
)

(state / "dev/batches/known-good/webgl.json").write_text(
    json.dumps(webgl_known_good, indent=2) + "\n"
)

manifest = {
    "schemaVersion": "1.0.0",
    "sourceCommit": commit,
    "sourceBranch": "develop",
    "dirty": False,
    "buildProfile": "release",
    "apiEnvironment": "Prod",
    "compression": "brotli+fallback",
}

(webgl_root / f"releases/{short}/manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n"
)

world_package_url = (
    "https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/"
    f"festa-world/{short}/festa-world-release-{short}.tar"
)

receipt = {
    "schemaVersion": "1.0.0",
    "state": "APPROVED_FOR_PRODUCTION",
    "receiptId": "production-fixture-1",
    "approvedAt": "2026-09-19T00:10:00Z",
    "approvedBy": "fixture-operator",
    "sourceBranch": "develop",
    "demoReleaseId": release_id,
    "applications": {
        name: components[name]
        for name in ("ai", "back", "front")
    },
    "webgl": {
        "packageName": "festa-webgl",
        "packageVersion": short,
        "packageUrl": webgl_package_url,
        "artifactSha256": "e" * 64,
        "sourceCommit": commit,
        "sourceBranch": "develop",
        "buildProfile": "release",
        "apiEnvironment": "Prod",
        "compression": "brotli+fallback",
    },
    "world": {
        "packageName": "festa-world",
        "packageVersion": short,
        "packageUrl": world_package_url,
        "archiveSha256": "f" * 64,
        "sourceCommit": commit,
        "sourceBranch": "develop",
        "imageRef": f"festa-game:{commit}",
        "imageContentId": ids["game"],
    },
    "compatibility": {
        "prefabTreeHash": prefab,
        "demoRuntimeVerified": True,
    },
    "evidence": {
        "environmentApprovedAt": "2026-09-19T00:01:00Z",
        "webglApprovedAt": "2026-09-19T00:02:00Z",
        "gameApprovedAt": "2026-09-19T00:03:00Z",
        "demoRuntimeVerificationRef": "fixture/demo-runtime.json",
    },
}

(work / "receipt.json").write_text(
    json.dumps(receipt, indent=2) + "\n"
)
PYFIXTURE

ln -s "releases/${short}" "${webgl_root}/current"

run_validator() {
  PRODUCTION_PROMOTION_RECEIPT_PATH="${work}/receipt.json" \
  ENVIRONMENT_STATE_DIR="${state}" \
  WEBGL_RELEASE_ROOT="${webgl_root}" \
  PYTHON_BIN=python3 \
    "${validator}"
}

output="$(run_validator)"
[[ "${output}" == "production-fixture-1" ]] \
  || fail "valid Production receipt was rejected"

# Live WebGL drift must fail closed.
mkdir -p "${webgl_root}/releases/drift000"
ln -sfn "releases/drift000" "${webgl_root}/current"

if run_validator >/dev/null 2>&1; then
  fail "live WebGL drift was accepted"
fi

ln -sfn "releases/${short}" "${webgl_root}/current"

# Named human approval is mandatory.
cp \
  "${state}/dev/batches/known-good/environment.json" \
  "${work}/environment-good.json"

python3 - "${state}/dev/batches/known-good/environment.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["approvedBy"] = None
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

if run_validator >/dev/null 2>&1; then
  fail "anonymous Demo environment approval was accepted"
fi

cp \
  "${work}/environment-good.json" \
  "${state}/dev/batches/known-good/environment.json"

# Exact World image identity must match Demo known-good.
cp "${work}/receipt.json" "${work}/receipt-good.json"

python3 - "${work}/receipt.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["world"]["imageContentId"] = "sha256:" + "9" * 64
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

if run_validator >/dev/null 2>&1; then
  fail "different Production World image was accepted"
fi

cp "${work}/receipt-good.json" "${work}/receipt.json"

# Schema state itself must be fail-closed.
python3 - "${work}/receipt.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["state"] = "ACTIVE"
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

if run_validator >/dev/null 2>&1; then
  fail "invalid Production receipt state was accepted"
fi

echo "PASS: Production promotion receipt binds human-approved Demo app, WebGL, World and prefab identity"
