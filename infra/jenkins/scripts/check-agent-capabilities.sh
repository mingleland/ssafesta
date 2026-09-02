#!/usr/bin/env bash
set -euo pipefail

required=(bash git python python3 node npm java javac curl jq openssl psql redis-cli docker unzip tar sha256sum)
missing=()
for command_name in "${required[@]}"; do
  command -v "${command_name}" >/dev/null 2>&1 || missing+=("${command_name}")
done

if (( ${#missing[@]} > 0 )); then
  printf 'AGENT_CAPABILITY_MISSING %s\n' "${missing[@]}" >&2
  exit 69
fi

python -c 'import sys; assert sys.version_info >= (3, 12), sys.version'
python -m pip --version >/dev/null
python -c 'import jsonschema; assert jsonschema.__version__ == "4.26.0", jsonschema.__version__'
node -e 'const major=Number(process.versions.node.split(".")[0]); if (major < 24) process.exit(1)'
[[ "$(javac -version 2>&1)" == javac\ 21.* ]]
docker version --format '{{.Server.Version}}' >/dev/null
docker compose version >/dev/null

printf 'AGENT_CAPABILITIES_OK python=%s node=%s java=%s docker=%s\n' \
  "$(python -c 'import platform; print(platform.python_version())')" \
  "$(node --version)" \
  "$(javac -version 2>&1 | awk '{print $2}')" \
  "$(docker version --format '{{.Server.Version}}')"
