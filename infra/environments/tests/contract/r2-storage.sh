#!/usr/bin/env bash
# R2 bucket·CORS 선언과 Cloudflare 수집기가 정한 사용량 입력 계약을 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
install_cleanup_trap

buckets="${repo_root}/infra/environments/storage/r2/buckets.example.yaml"
cors="${repo_root}/infra/environments/storage/r2/documents-cors.json"
limits="${repo_root}/infra/environments/storage/usage-guard/limits.example.json"
collector="${repo_root}/infra/environments/storage/usage-guard/collect-cloudflare.sh"

assert_contains "${buckets}" 'publicAccess: false' 'R2 buckets must remain private'
assert_contains "${buckets}" 'R2_DOCUMENT_SIGNER_CREDENTIAL_REF' 'document signer reference is missing'
assert_contains "${buckets}" 'R2_BACKUP_WRITER_CREDENTIAL_REF' 'backup writer reference is missing'
assert_contains "${buckets}" 'cors: disabled' 'backup bucket CORS must be disabled'

python3 - "${cors}" <<'PY'
import json
import pathlib
import sys
rule = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))[0]
assert rule["AllowedMethods"] == ["PUT"]
assert "*" not in rule["AllowedOrigins"]
assert set(rule["AllowedHeaders"]) == {"Content-Type", "x-amz-checksum-sha256"}
assert rule["ExposeHeaders"] == ["ETag"]
PY

temp_dir="$(mktemp -d)"
register_cleanup "${temp_dir}"
python3 - "${temp_dir}/graphql.json" <<'PY'
import pathlib
import sys
pathlib.Path(sys.argv[1]).write_text('{"data":{"viewer":{"accounts":[{"r2OperationsAdaptiveGroups":[{"dimensions":{"action":"PutObject"},"sum":{"requests":3}},{"dimensions":{"action":"GetObject"},"sum":{"requests":5}}],"r2StorageAdaptiveGroups":[{"max":{"payloadSize":1073741824}}]}]}}}', encoding="utf-8")
PY

bash "${collector}" --limits "${limits}" --response-file "${temp_dir}/graphql.json" --output "${temp_dir}/collected.json"
python3 - "${temp_dir}/collected.json" <<'PY'
import json
import pathlib
import sys
result = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert result["currentStorageBytes"] == 1073741824
assert result["classARequests"] == 3
assert result["classBRequests"] == 5
PY

pass 'R2 bucket, CORS, and account-wide collector contracts hold'
