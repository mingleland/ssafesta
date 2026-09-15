#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "Usage: $0 <rule-file-or-directory>" >&2; exit 64; }
target="$1"; [[ -e "${target}" ]] || { echo "rule target not found: ${target}" >&2; exit 66; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
files=(); if [[ -d "${target}" ]]; then while IFS= read -r file; do files+=("${file}"); done < <(find "${target}" -maxdepth 1 -type f -name '*.json' | sort); else files+=("${target}"); fi
[[ ${#files[@]} -gt 0 ]] || { echo 'no detection rule JSON files found' >&2; exit 66; }
bash "${repo_root}/infra/jenkins/scripts/validate-contracts.sh" detection-rule "${files[@]}"
native_files=(); for file in "${files[@]}"; do if command -v cygpath >/dev/null 2>&1; then native_files+=("$(cygpath -w "${file}")"); else native_files+=("${file}"); fi; done
python - "${native_files[@]}" <<'PY'
import datetime,json,pathlib,re,sys
now=datetime.datetime.now(datetime.timezone.utc); forbidden_labels={'requestid','request_id','userid','user_id','email','phone','ip','traceid','trace_id','token'}
bad=[]
for arg in sys.argv[1:]:
 p=pathlib.Path(arg); d=json.loads(p.read_text(encoding='utf-8'))
 if d['scope'].get('domain')=='ci': bad.append(f'{p}: domain=ci cannot notify')
 if not d.get('enabled'): bad.append(f'{p}: disabled rule cannot be routed')
 if d['approval'].get('status')!='approved': bad.append(f'{p}: approval missing')
 if not d['annotations'].get('runbookUrl'): bad.append(f'{p}: runbook required')
 labels={key.lower() for key in d.get('labels',{})}
 if labels & forbidden_labels: bad.append(f'{p}: unbounded or sensitive labels: {sorted(labels & forbidden_labels)}')
 expiry=d.get('expiresAt')
 if expiry and datetime.datetime.fromisoformat(expiry.replace('Z','+00:00')) <= now: bad.append(f'{p}: rule expired')
 raw=json.dumps(d)
 if re.search(r'(?i)authorization\s*[:=]\s*(bearer|basic)|https?://[^\s/]+/(hooks|webhooks)/',raw): bad.append(f'{p}: sensitive value')
if bad: print('\n'.join(bad),file=sys.stderr); raise SystemExit(1)
print(f'RULE_POLICY_OK count={len(sys.argv)-1}')
PY
