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
elif [[ "$operation" == delete-object ]]; then
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --key) key="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  target_file="${FAKE_BUCKET}/${key}"
  rm -f "$target_file"
  exit 0
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
if [[ "$operation" == ls ]]; then
  target_prefix="${1#s3://${R2_BACKUP_BUCKET}/}"
  target_dir="${FAKE_BUCKET}/${target_prefix}"
  if [[ -d "${target_dir}" ]]; then
    (cd "${FAKE_BUCKET}" && find "${target_prefix}" -type f -printf "2026-09-16 00:00:00 1234 %p\n")
  fi
  exit 0
fi
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
assert_contains "$temp_dir/state/awscli-r2.conf" 'addressing_style = path' 'R2 requests use path-style addressing'
assert_contains "$temp_dir/state/awscli-r2.conf" 'request_checksum_calculation = when_required' 'R2 uploads omit optional trailer checksums'
assert_contains "$temp_dir/bucket/$manifest_key" 'schemaSha256' 'manifest records schema fingerprint'
assert_contains "$temp_dir/bucket/$manifest_key" 'tableRows' 'manifest records exact row counts'

cat >"$temp_dir/bin/aws-trace" <<'SH'
#!/usr/bin/env bash
if [[ "$1" == --debug ]]; then
  printf 'CanonicalRequest:\nHEAD\n/postgres-backups\nhost:r2.example.invalid\nStringToSign:\n20260913T155457Z\n20260913/auto/s3/aws4_request\ncanonical-request-sha256\n' >&2
  printf 'Authorization: must-not-be-printed\n' >&2
fi
exit 254
SH
chmod +x "$temp_dir/bin/aws-trace"
set +e
trace_output="$(AWS_CLI="$temp_dir/bin/aws-trace" R2_BACKUP_ENDPOINT='https://r2.example.invalid' R2_BACKUP_BUCKET='postgres-backups' bash -c "source '$repo_root/infra/environments/postgres/backup/lib.sh'; backup_s3api put-object --bucket postgres-backups --key trace --body /dev/null" 2>&1)"
trace_status=$?
set -e
[[ "$trace_status" -eq 254 ]] || fail 'R2 trace changed the upload failure status'
[[ "$trace_output" == *'CanonicalRequest:'* ]] || fail 'R2 trace omitted canonical request'
[[ "$trace_output" == *'20260913/auto/s3/aws4_request'* ]] || fail 'R2 trace omitted signing scope'
[[ "$trace_output" != *'must-not-be-printed'* ]] || fail 'R2 trace exposed authorization data'

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

# --------------------------------------------------------------------------
# Test T058: retention policy (daily 7, weekly 4, simulation / dry-run)
# --------------------------------------------------------------------------
retention_work="$temp_dir/retention_test"
mkdir -p "$retention_work"

# Mock multiple daily manifests in FAKE_BUCKET
for i in $(seq -w 1 10); do
  m_dir="$temp_dir/bucket/postgresql/demo/daily/2026/09/${i}/demo-daily-202609${i}T000000Z"
  mkdir -p "$m_dir"
  echo '{"manifest":true}' > "$m_dir/manifest.json"
  echo "dump ${i}" > "$m_dir/festa_demo_business.dump"
done

# Run retention in dry-run mode
dry_run_out="$(bash "$repo_root/infra/environments/postgres/backup/retention.sh" --environment demo --tier daily --dry-run)"
assert_contains "$dry_run_out" '"tier":"daily"' 'retention reports daily tier'
assert_contains "$dry_run_out" '"totalFound":10' 'retention finds all 10 manifests'
assert_contains "$dry_run_out" '"kept":7' 'retention keeps 7 newest'
assert_contains "$dry_run_out" '"deletedCount":6' 'retention plans deletion of 3 sets * 2 files = 6 objects'
assert_contains "$dry_run_out" '"mode":"dry-run"' 'retention reports dry-run mode'

# Verify files still exist after dry-run
assert_file "$temp_dir/bucket/postgresql/demo/daily/2026/09/01/demo-daily-20260901T000000Z/manifest.json"

# Run retention in apply mode
apply_out="$(bash "$repo_root/infra/environments/postgres/backup/retention.sh" --environment demo --tier daily --apply)"
assert_contains "$apply_out" '"mode":"apply"' 'retention runs in apply mode'

# The oldest 3 sets (01, 02, 03) should be deleted
if [[ -f "$temp_dir/bucket/postgresql/demo/daily/2026/09/01/demo-daily-20260901T000000Z/manifest.json" ]]; then
  fail 'oldest daily backup was not deleted in apply mode'
fi
# The 7 newest sets (04..10) should still exist
assert_file "$temp_dir/bucket/postgresql/demo/daily/2026/09/10/demo-daily-20260910T000000Z/manifest.json"
assert_file "$temp_dir/bucket/postgresql/demo/daily/2026/09/04/demo-daily-20260904T000000Z/manifest.json"

pass 'PostgreSQL backup retention enforces daily 7 limit with simulated and real deletion'
