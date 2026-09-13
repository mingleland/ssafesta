#!/usr/bin/env bash
# Restores a checksum-verified backup only into explicitly disposable databases.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/lib.sh"

target= manifest_key="${BACKUP_MANIFEST_KEY:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) target="${2:-}"; shift 2 ;;
    --manifest-key) manifest_key="${2:-}"; shift 2 ;;
    *) backup_die 'usage: restore.sh --target disposable-name [--manifest-key key]' ;;
  esac
done
[[ -n "$target" && -n "$manifest_key" ]] || backup_die 'target and manifest key are required'
backup_init
# Validate before any download or database command; a live name must never reach pg_restore.
backup_target_database "$target" festa_dev_business >/dev/null
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT HUP INT TERM
backup_aws cp "s3://${R2_BACKUP_BUCKET}/${manifest_key}" "${work}/manifest.json" --only-show-errors
backup_python - "${work}/manifest.json" <<'PY'
import json,re,sys
d=json.load(open(sys.argv[1])); assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}',d['backupSetId'])
assert d['environment'] in ('dev','demo') and len(d['databases']) == 2
for item in d['databases']:
 assert re.fullmatch(r'[0-9a-f]{64}',item['sha256']) and item['dumpKey'].startswith('postgresql/')
PY

backup_set_id="$(backup_python - "${work}/manifest.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['backupSetId'])
PY
)"
restore_rows="${work}/restore.tsv"
while IFS= read -r source_db; do
  restore_db="$(backup_target_database "$target" "$source_db")"
  read -r dump_key expected_sha < <(backup_python - "${work}/manifest.json" "$source_db" <<'PY'
import json,sys
item=next(x for x in json.load(open(sys.argv[1]))['databases'] if x['database'] == sys.argv[2])
print(item['dumpKey'], item['sha256'])
PY
)
  printf '%s\t%s\t%s\t%s\n' "$source_db" "$restore_db" "$dump_key" "$expected_sha" >>"$restore_rows"
  backup_aws cp "s3://${R2_BACKUP_BUCKET}/${dump_key}" "${work}/${source_db}.dump" --only-show-errors
  actual_sha="$(sha256sum "${work}/${source_db}.dump" | awk '{print $1}')"
  [[ "$actual_sha" == "$expected_sha" ]] || backup_die "checksum mismatch: $source_db"
  backup_psql postgres -c "DROP DATABASE IF EXISTS \"${restore_db}\""
  backup_psql postgres -c "CREATE DATABASE \"${restore_db}\""
  "${DOCKER_BIN}" exec -i -e PGPASSWORD "${POSTGRES_CONTAINER}" pg_restore -U festa_admin -d "$restore_db" --no-owner --no-privileges <"${work}/${source_db}.dump"
done < <(backup_databases "$(backup_python - "${work}/manifest.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['environment'])
PY
)")
mkdir -p "${BACKUP_STATE_DIR}/restores"
state="${BACKUP_STATE_DIR}/restores/${target}.json"
backup_python - "$state" "${work}/manifest.json" "$restore_rows" <<'PY'
import datetime,json,pathlib,sys
out,manifest,rows=map(pathlib.Path,sys.argv[1:]); d=json.loads(manifest.read_text())
targets=[]
for line in rows.read_text().splitlines():
 source,target,*_=line.split('\t'); targets.append({'sourceDatabase':source,'targetDatabase':target})
out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps({'backupSetId':d['backupSetId'],'manifest':d,'targets':targets,'restoredAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')},indent=2)+'\n')
PY
printf '{"backupSetId":"%s","target":"%s","restoreState":"%s"}\n' "$backup_set_id" "$target" "$state"
