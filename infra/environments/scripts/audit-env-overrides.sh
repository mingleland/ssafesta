#!/usr/bin/env bash
# env-file과 compose 정의가 같은 키를 동시에 정하는 지점을 드러낸다.
#
# compose environment가 env_file보다 이긴다 — 그래서 env-file 쪽 값은 조용히 무시되고 정본이
# 어디인지 헷갈린 채 남는다(demo-back.env의 WORLD_HOST가 그렇게 어긋나 있었다).
# 배포를 막지는 않는다. 경고만 남기고 항상 0으로 끝낸다.
set -euo pipefail

usage() {
  echo "usage: $0 --env-file <path> --compose-config <json> --service <name>" >&2
}

env_file=''
compose_config=''
service=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="${2:-}"; shift 2 ;;
    --compose-config) compose_config="${2:-}"; shift 2 ;;
    --service) service="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done
[[ -n "${env_file}" && -n "${compose_config}" && -n "${service}" ]] || { usage; exit 64; }
[[ -r "${env_file}" && -r "${compose_config}" ]] || { echo 'audit-env-overrides: unreadable input' >&2; exit 64; }

python3 - "${env_file}" "${compose_config}" "${service}" <<'PY'
import json, pathlib, sys

env_path, config_path, service = sys.argv[1], sys.argv[2], sys.argv[3]

env_keys = set()
for line in pathlib.Path(env_path).read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    env_keys.add(line.split("=", 1)[0].strip())

config = json.loads(pathlib.Path(config_path).read_text(encoding="utf-8"))
environment = config.get("services", {}).get(service, {}).get("environment", {})
if isinstance(environment, list):
    compose_keys = {item.split("=", 1)[0] for item in environment}
else:
    compose_keys = set(environment)

overridden = sorted(env_keys & compose_keys)
print("envOverrides: " + (",".join(overridden) if overridden else "none"))

# 런타임 목적지·자격을 가리키는 키가 두 곳에서 정해지면 실제로 사고가 난다 — 이름을 찍어 둔다.
suspicious = [key for key in overridden if key.startswith("WORLD_") or key.endswith("DATABASE_URL")]
if suspicious:
    print(
        "audit-env-overrides: compose wins over env-file for "
        + ",".join(suspicious)
        + " — env-file 값은 무시된다. 정본을 한쪽으로 정리하라.",
        file=sys.stderr,
    )
PY
