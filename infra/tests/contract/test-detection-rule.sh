#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
approved_fixture="${script_dir}/fixtures/detection-rule-valid.json"

bash "${repo_root}/infra/observability/scripts/validate-rules.sh" "${approved_fixture}"
if bash "${repo_root}/infra/observability/scripts/validate-rules.sh" "${script_dir}/fixtures/detection-rule-ci.json" >/dev/null 2>&1; then
  echo 'FAIL: CI-domain detection rule was accepted' >&2; exit 1
fi
if bash "${repo_root}/infra/observability/scripts/validate-rules.sh" "${script_dir}/fixtures/detection-rule-expired.json" >/dev/null 2>&1; then
  echo 'FAIL: expired detection rule was accepted' >&2; exit 1
fi
echo 'PASS: detection rules require approval, bounded labels, runbook and valid lifetime'
