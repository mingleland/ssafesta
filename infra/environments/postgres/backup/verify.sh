#!/usr/bin/env bash
# Verifies restored databases against manifest metadata without printing credentials or document bodies.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/lib.sh"

target=
while [[ $# -gt 0 ]]; do case "$1" in --target) target="${2:-}"; shift 2;; *) backup_die 'usage: verify.sh --target disposable-name';; esac; done
[[ -n "$target" ]] || backup_die 'target is required'
backup_init
state="${BACKUP_STATE_DIR}/restores/${target}.json"
[[ -f "$state" ]] || backup_die 'restore state is missing'
while IFS=$'\t' read -r source_db restore_db expected_vector expected_tables expected_schema_sha; do
  actual_vector="$(backup_psql "$restore_db" -Atc "SELECT COALESCE((SELECT extversion FROM pg_extension WHERE extname='vector'),'absent')")"
  actual_tables="$(backup_psql "$restore_db" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema')")"
  [[ "$actual_vector" == "$expected_vector" ]] || backup_die "pgvector mismatch: $source_db"
  [[ "$actual_tables" == "$expected_tables" ]] || backup_die "table count mismatch: $source_db"
  actual_schema_sha="$(backup_docker_exec pg_dump -U festa_admin -d "$restore_db" --schema-only --no-owner --no-privileges | sha256sum | awk '{print $1}')"
  [[ "$actual_schema_sha" == "$expected_schema_sha" ]] || backup_die "schema mismatch: $source_db"
  while IFS=$'\t' read -r table expected_rows; do
    actual_rows="$(backup_psql "$restore_db" -Atc "SELECT count(*) FROM ${table}")"
    [[ "$actual_rows" == "$expected_rows" ]] || backup_die "row count mismatch: ${source_db}/${table}"
  done < <(backup_python - "$state" "$source_db" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); item=next(x for x in d['manifest']['databases'] if x['database'] == sys.argv[2])
for row in item['tableRows']: print(row['table'], row['rows'], sep='\t')
PY
)
done < <(backup_python - "$state" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); targets={x['sourceDatabase']:x['targetDatabase'] for x in d['targets']}
for item in d['manifest']['databases']:
 print(item['database'],targets[item['database']],item['pgvectorVersion'],item['tableCount'],item['schemaSha256'],sep='\t')
PY
)
printf '{"backupSetId":"%s","target":"%s","status":"RESTORE_VERIFIED","documentInventoryRef":"%s"}\n' \
  "$(backup_python - "$state" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['backupSetId'])
PY
)" "$target" \
  "$(backup_python - "$state" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['manifest']['documentInventoryRef'])
PY
)"
