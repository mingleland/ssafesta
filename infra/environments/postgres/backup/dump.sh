#!/usr/bin/env bash
# Creates custom-format PostgreSQL dumps, records integrity metadata, then uploads only to R2.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/lib.sh"

environment= tier= release_id="${RELEASE_ID:-}" document_inventory_ref="${DOCUMENT_INVENTORY_REF:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment="${2:-}"; shift 2 ;;
    --tier) tier="${2:-}"; shift 2 ;;
    *) backup_die 'usage: dump.sh --environment dev|demo --tier daily|weekly|pre-migration|manual-test' ;;
  esac
done
[[ "$tier" =~ ^(daily|weekly|pre-migration|manual-test)$ ]] || backup_die 'invalid backup tier'
backup_databases "$environment" >/dev/null
[[ -n "$document_inventory_ref" ]] || backup_die 'DOCUMENT_INVENTORY_REF is required'
[[ -n "$release_id" ]] || backup_die 'RELEASE_ID is required'
backup_init

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_set_id="${BACKUP_SET_ID:-${environment}-${tier}-${stamp}}"
backup_safe_id "$backup_set_id"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "${BACKUP_STATE_DIR}/manifests"

case "$tier" in
  daily) object_prefix="postgresql/${environment}/daily/$(date -u +%Y/%m/%d)/${backup_set_id}" ;;
  weekly) object_prefix="postgresql/${environment}/weekly/$(date -u +%G-W%V)/${backup_set_id}" ;;
  pre-migration) object_prefix="postgresql/${environment}/pre-migration/${release_id}/${backup_set_id}" ;;
  manual-test) object_prefix="postgresql/${environment}/manual/${backup_set_id}" ;;
esac

manifest_input="${work}/databases.jsonl"
postgres_version="$(backup_psql postgres -Atc 'SHOW server_version')"
while IFS= read -r database; do
  dump="${work}/${database}.dump"
  schema_dump="${work}/${database}.schema.sql"
  "${DOCKER_BIN}" exec "${POSTGRES_CONTAINER}" pg_dump -U festa_admin -d "$database" -Fc >"$dump"
  "${DOCKER_BIN}" exec "${POSTGRES_CONTAINER}" pg_dump -U festa_admin -d "$database" --schema-only --no-owner --no-privileges >"$schema_dump"
  [[ -s "$dump" ]] || backup_die "empty dump: $database"
  sha="$(sha256sum "$dump" | awk '{print $1}')"
  bytes="$(wc -c <"$dump" | tr -d ' ')"
  vector_version="$(backup_psql "$database" -Atc "SELECT COALESCE((SELECT extversion FROM pg_extension WHERE extname='vector'),'absent')")"
  tables="$(backup_psql "$database" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema')")"
  schema_sha="$(sha256sum "$schema_dump" | awk '{print $1}')"
  table_rows="${work}/${database}.table-rows.tsv"
  : >"$table_rows"
  while IFS= read -r table; do
    [[ -n "$table" ]] || continue
    printf '%s\t%s\n' "$table" "$(backup_psql "$database" -Atc "SELECT count(*) FROM ${table}")" >>"$table_rows"
  done < <(backup_psql "$database" -Atc "SELECT format('%I.%I', table_schema, table_name) FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_schema, table_name")
  backup_python - "$manifest_input" "$database" "${object_prefix}/${database}.dump" "$sha" "$bytes" "$vector_version" "$tables" "$schema_sha" "$table_rows" <<'PY'
import json, pathlib, sys
out, name, key, sha, size, vector, tables, schema_sha, rows = sys.argv[1:]
table_rows = [{'table': table, 'rows': int(count)} for table, count in (line.split('\t', 1) for line in pathlib.Path(rows).read_text().splitlines() if line)]
with pathlib.Path(out).open('a', encoding='utf-8') as handle:
    handle.write(json.dumps({'database': name, 'dumpKey': key, 'sha256': sha, 'sizeBytes': int(size), 'pgvectorVersion': vector, 'tableCount': int(tables), 'schemaSha256': schema_sha, 'tableRows': table_rows}) + '\n')
PY
done < <(backup_databases "$environment")

manifest="${work}/${backup_set_id}.manifest.json"
backup_python - "$manifest" "$manifest_input" "$backup_set_id" "$environment" "$tier" "$release_id" "$postgres_version" "$document_inventory_ref" <<'PY'
import datetime,json,pathlib,sys
out, rows, backup_id, env, tier, release, postgres, inventory = sys.argv[1:]
items=[json.loads(line) for line in pathlib.Path(rows).read_text().splitlines() if line]
pathlib.Path(out).write_text(json.dumps({'schemaVersion':'1.0.0','backupSetId':backup_id,'environment':env,'tier':tier,'sourceReleaseId':release or None,'postgresVersion':postgres,'documentInventoryRef':inventory,'databases':items,'createdAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')},indent=2)+'\n')
PY

while IFS=$'\t' read -r database key; do backup_aws cp "${work}/${database}.dump" "s3://${R2_BACKUP_BUCKET}/${key}" --only-show-errors; done < <(backup_python - "$manifest_input" <<'PY'
import json, pathlib, sys
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    item=json.loads(line); print(item['database'], item['dumpKey'], sep='\t')
PY
)
manifest_key="${object_prefix}/manifest.json"
backup_aws cp "$manifest" "s3://${R2_BACKUP_BUCKET}/${manifest_key}" --only-show-errors
cp "$manifest" "${BACKUP_STATE_DIR}/manifests/${backup_set_id}.json"
printf '{"backupSetId":"%s","manifestKey":"%s","status":"UPLOADED_UNVERIFIED"}\n' "$backup_set_id" "$manifest_key"
