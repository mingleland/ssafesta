#!/usr/bin/env bash
set -euo pipefail

: "${WORLD_SOURCE_COMMIT:?WORLD_SOURCE_COMMIT is required}"
: "${WORLD_IMAGE_REF:?WORLD_IMAGE_REF is required}"
: "${WORLD_CONTENT_ID:?WORLD_CONTENT_ID is required}"
: "${WORLD_PUBLISHED_BY:?WORLD_PUBLISHED_BY is required}"

[[ "${WORLD_SOURCE_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || {
  echo "invalid WORLD_SOURCE_COMMIT" >&2
  exit 64
}

[[ "${WORLD_CONTENT_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "invalid WORLD_CONTENT_ID" >&2
  exit 64
}

[[ "${WORLD_PUBLISHED_BY}" =~ ^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$ ]] || {
  echo "invalid WORLD_PUBLISHED_BY" >&2
  exit 64
}

state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"

known_good_game="${KNOWN_GOOD_GAME_STATE_PATH:-${state_root}/demo/game/known-good.json}"
known_good_environment="${KNOWN_GOOD_ENVIRONMENT_PATH:-${state_root}/dev/batches/known-good/environment.json}"

[[ -f "${known_good_game}" ]] || {
  echo "World publication denied: Demo game known-good state is missing" >&2
  exit 66
}

[[ -f "${known_good_environment}" ]] || {
  echo "World publication denied: Demo environment known-good state is missing" >&2
  exit 66
}

python_bin="${PYTHON_BIN:-python3}"

"${python_bin}" - \
  "${known_good_game}" \
  "${known_good_environment}" \
  "${WORLD_SOURCE_COMMIT}" \
  "${WORLD_IMAGE_REF}" \
  "${WORLD_CONTENT_ID}" <<'PY_VALIDATE_WORLD_SOURCE'
import json
import pathlib
import sys

(
    game_path,
    environment_path,
    source_commit,
    image_ref,
    content_id,
) = sys.argv[1:]

game = json.loads(
    pathlib.Path(game_path).read_text(encoding="utf-8")
)

environment = json.loads(
    pathlib.Path(environment_path).read_text(encoding="utf-8")
)

if game.get("state") != "KNOWN_GOOD":
    raise SystemExit(
        "World publication denied: Demo game is not KNOWN_GOOD"
    )

expected = {
    "sourceCommit": source_commit,
    "imageRef": image_ref,
    "contentId": content_id,
}

for key, value in expected.items():
    if game.get(key) != value:
        raise SystemExit(
            f"World publication denied: Demo game {key} mismatch"
        )

if environment.get("state") != "KNOWN_GOOD":
    raise SystemExit(
        "World publication denied: Demo environment is not KNOWN_GOOD"
    )

components = environment.get("components")
if not isinstance(components, dict):
    raise SystemExit(
        "World publication denied: Demo environment components are invalid"
    )

approved_game = components.get("game")
if not isinstance(approved_game, dict):
    raise SystemExit(
        "World publication denied: Demo environment has no approved game"
    )

for key, value in expected.items():
    if approved_game.get(key) != value:
        raise SystemExit(
            f"World publication denied: environment/game {key} mismatch"
        )

if approved_game.get("releaseId") != game.get("releaseId"):
    raise SystemExit(
        "World publication denied: game releaseId mismatch"
    )

print(game["releaseId"])
PY_VALIDATE_WORLD_SOURCE
