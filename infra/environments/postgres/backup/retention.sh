#!/usr/bin/env bash
# Applies retention policy to PostgreSQL backups stored in R2:
#   - daily: keep newest 7
#   - weekly: keep newest 4
#   - pre-migration: kept indefinitely (manual/tied to release)
# Supports --dry-run (default: true if not explicitly --apply)
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/lib.sh"

environment=
tier="all"
mode="dry-run"
limit_daily=7
limit_weekly=4

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment="${2:-}"; shift 2 ;;
    --tier) tier="${2:-}"; shift 2 ;;
    --apply) mode="apply"; shift ;;
    --dry-run) mode="dry-run"; shift ;;
    *) backup_die 'usage: retention.sh --environment dev|demo [--tier daily|weekly|all] [--apply|--dry-run]' ;;
  esac
done

[[ "$environment" =~ ^(dev|demo)$ ]] || backup_die 'environment must be dev or demo'
[[ "$tier" =~ ^(daily|weekly|all)$ ]] || backup_die 'tier must be daily, weekly, or all'
backup_init

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM

# List manifests under a tier prefix
list_manifests_for_tier() {
  local env_name="$1" target_tier="$2"
  local prefix="postgresql/${env_name}/${target_tier}/"
  backup_aws ls "s3://${R2_BACKUP_BUCKET}/${prefix}" --recursive 2>/dev/null | \
    awk '{print $4}' | grep '/manifest\.json$' || true
}

# Resolve all objects associated with a manifest
list_backup_set_objects() {
  local manifest_key="$1"
  local manifest_dir="${manifest_key%/manifest.json}"
  backup_aws ls "s3://${R2_BACKUP_BUCKET}/${manifest_dir}/" --recursive 2>/dev/null | \
    awk '{print $4}' || true
}

process_tier() {
  local target_tier="$1" max_keep="$2"
  local manifests_file="${work}/manifests_${target_tier}.txt"
  list_manifests_for_tier "${environment}" "${target_tier}" | sort -r > "${manifests_file}"

  local total=0
  while IFS= read -r line; do
    [[ -n "$line" ]] && ((total++)) || true
  done < "${manifests_file}"

  local keep_count=0
  local delete_candidates_file="${work}/delete_${target_tier}.txt"
  : > "${delete_candidates_file}"

  while IFS= read -r manifest_key; do
    [[ -n "$manifest_key" ]] || continue
    if (( keep_count < max_keep )); then
      ((++keep_count))
    else
      list_backup_set_objects "${manifest_key}" >> "${delete_candidates_file}"
    fi
  done < "${manifests_file}"

  local delete_count=0
  while IFS= read -r obj; do
    [[ -n "$obj" ]] || continue
    ((++delete_count))
    if [[ "$mode" == "apply" ]]; then
      backup_s3api delete-object --bucket "${R2_BACKUP_BUCKET}" --key "$obj" >/dev/null
    fi
  done < "${delete_candidates_file}"

  printf '{"tier":"%s","totalFound":%d,"kept":%d,"deletedCount":%d,"mode":"%s"}\n' \
    "$target_tier" "$total" "$keep_count" "$delete_count" "$mode"
}

printf '{"environment":"%s","mode":"%s","action":"retention_eval"}\n' "$environment" "$mode"

if [[ "$tier" == "daily" || "$tier" == "all" ]]; then
  process_tier "daily" "$limit_daily"
fi

if [[ "$tier" == "weekly" || "$tier" == "all" ]]; then
  process_tier "weekly" "$limit_weekly"
fi

