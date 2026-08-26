#!/usr/bin/env bash
set -euo pipefail

: "${STATE_DIR:?}"
state="${STATE_DIR}/target-state.json"
[[ -f "${state}" ]] || { echo "no promoted release for ${STATE_DIR}" >&2; exit 66; }
native="${state}"; if command -v cygpath >/dev/null 2>&1; then native="$(cygpath -w "${state}")"; fi
python - "${native}" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
print(f"target={d.get('targetId')} sequence={d.get('releaseSequence')}")
print(f"current={d.get('currentReleaseId')} known-good={d.get('knownGoodReleaseId')} previous={d.get('previousKnownGoodReleaseId')}")
PY
