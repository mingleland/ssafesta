#!/usr/bin/env bash
# Exercises the R2-only PostgreSQL rehearsal path with local command doubles; no live database is touched.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
install_cleanup_trap

temp_dir="$(mktemp -d)"
register_cleanup "$temp_dir"
mkdir -p "$temp_dir/bin" "$temp_dir/bucket"

cat >"$temp_dir/bin/aws" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
while [[ "$1" != cp && "$1" != put-object ]]; do shift; done
operation="$1"; shift
if [[ "$operation" == put-object ]]; then
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --key) key="$2"; shift 2 ;;
      --body) source="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  destination="s3://${R2_BACKUP_BUCKET}/${key}"
else
  source="$1"; destination="$2"
fi
map_path() {
  if [[ "$1" == s3://* ]]; then
    object="${1#s3://}"; printf '%s/%s\n' "$FAKE_BUCKET" "${object#*/}"
  else
    printf '%s\n' "$1"
  fi
}
source="$(map_path "$source")"; destination="$(map_path "$destination")"
mkdir -p "$(dirname "$destination")"; cp "$source" "$destination"
SH
cat >"$temp_dir/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == exec ]] || exit 2; shift
[[ "${1:-}" == -i ]] && shift
[[ "${1:-}" == -e ]] && shift 2
shift # container
command="$1"; shift
if [[ "$command" == pg_dump ]]; then
  database=; schema=false
  while [[ $# -gt 0 ]]; do case "$1" in -d) database="$2"; shift 2;; --schema-only) schema=true; shift;; *) shift;; esac; done
  if "$schema"; then printf 'CREATE TABLE public.documents (id integer);\n'; else printf 'custom dump for %s\n' "$database"; fi
  exit 0
fi
if [[ "$command" == pg_restore ]]; then cat >/dev/null; printf 'pg_restore\n' >>"$FAKE_CALLS"; exit 0; fi
if [[ "$command" == psql ]]; then
  query="${*: -1}"
  if [[ "$query" == *server_version* ]]; then printf '17.5\n'
  elif [[ "$query" == *extversion* ]]; then printf '0.8.0\n'
  elif [[ "$query" == *"format("* ]]; then printf 'public.documents\n'
  elif [[ "$query" == *"information_schema.tables"* ]]; then printf '1\n'
  elif [[ "$query" == *"public.documents"* ]]; then printf '2\n'
  else printf 'ok\n'; fi
  exit 0
fi
exit 2
SH
chmod +x "$temp_dir/bin/aws" "$temp_dir/bin/docker"

export PATH="$temp_dir/bin:$PATH" FAKE_BUCKET="$temp_dir/bucket" FAKE_CALLS="$temp_dir/calls"
export R2_BACKUP_ENDPOINT='https://r2.example.invalid' R2_BACKUP_BUCKET='postgres-backups'
export AWS_ACCESS_KEY_ID='test-key' AWS_SECRET_ACCESS_KEY='test-secret' BACKUP_STATE_DIR="$temp_dir/state"
export PGPASSWORD='test-postgres-password'
export DOCUMENT_INVENTORY_REF='inventory://demo/2026-09-13' RELEASE_ID='test-release' BACKUP_SET_ID='demo-test-backup'
export PYTHON_BIN="${PYTHON_BIN:-python3}"

dump_output="$(bash "$repo_root/infra/environments/postgres/backup/dump.sh" --environment demo --tier manual-test)"
manifest_key="$("$PYTHON_BIN" -c "import json; print(json.loads('''$dump_output''')['manifestKey'])")"
assert_file "$temp_dir/bucket/$manifest_key"
assert_contains "$temp_dir/bucket/$manifest_key" 'schemaSha256' 'manifest records schema fingerprint'
assert_contains "$temp_dir/bucket/$manifest_key" 'tableRows' 'manifest records exact row counts'

if bash "$repo_root/infra/environments/postgres/backup/restore.sh" --target live-demo --manifest-key "$manifest_key" >/dev/null 2>&1; then
  fail 'live target was accepted'
fi

first_dump="$(find "$temp_dir/bucket" -name '*.dump' | head -n 1)"
printf 'corrupted\n' >>"$first_dump"
if bash "$repo_root/infra/environments/postgres/backup/restore.sh" --target disposable-demo-restore --manifest-key "$manifest_key" >/dev/null 2>&1; then
  fail 'checksum mismatch was accepted'
fi
[[ ! -f "$temp_dir/calls" ]] || fail 'pg_restore ran after checksum mismatch'

pass 'PostgreSQL backup rejects live targets and checksum corruption before restoring disposable databases'
