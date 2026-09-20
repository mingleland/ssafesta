#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
backup="${root}/infra/jenkins/pipelines/postgres-backup.groovy"
restore="${root}/infra/jenkins/pipelines/postgres-restore-rehearsal.groovy"

grep -q "?: 'r2-postgres-backup-writer'" "$backup"
grep -q "?: 'r2-postgres-backup-reader'" "$restore"
grep -q "?: 'postgres-backup-admin-password'" "$backup"
grep -q "?: 'postgres-backup-admin-password'" "$restore"
printf 'PASS: PostgreSQL Jenkins credential IDs have non-null defaults\n'
