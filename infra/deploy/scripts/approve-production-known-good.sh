#!/usr/bin/env bash
# 사람의 public 검증을 통과한 Production CURRENT만 KNOWN-GOOD로 승인한다.
set -euo pipefail
set +x
[[ $# -eq 1 ]] || { echo 'Usage: approve-production-known-good.sh <receipt-id>' >&2; exit 64; }
: "${APPROVED_BY:?APPROVED_BY is required}"
receipt_id="$1"; state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
current="${state_root}/production/current.json"; external="${state_root}/production/receipts/${receipt_id}.external-verification.json"; known_good="${state_root}/production/known-good.json"
for path in "${current}" "${external}"; do [[ -f "${path}" ]] || { echo "missing approval evidence: ${path}" >&2; exit 66; }; done
CURRENT="${current}" EXTERNAL="${external}" KNOWN_GOOD="${known_good}" RECEIPT_ID="${receipt_id}" APPROVED_BY="${APPROVED_BY}" python3 - <<'PY'
import datetime,hashlib,json,os,pathlib
current_path=pathlib.Path(os.environ['CURRENT']); c=json.loads(current_path.read_text(encoding='utf-8')); e=json.loads(pathlib.Path(os.environ['EXTERNAL']).read_text(encoding='utf-8'))
rid=os.environ['RECEIPT_ID']
if c.get('state')!='CURRENT' or c.get('receiptId')!=rid: raise SystemExit('known-good approval requires matching CURRENT')
if e.get('state')!='EXTERNAL_VERIFIED' or e.get('receiptId')!=rid: raise SystemExit('known-good approval requires matching external verification')
if e.get('currentSha256')!=hashlib.sha256(current_path.read_bytes()).hexdigest(): raise SystemExit('CURRENT changed after external verification')
d=dict(c); d['state']='KNOWN_GOOD'; d['approvedBy']=os.environ['APPROVED_BY']; d['approvedAt']=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'); d['approvedFromCurrent']=rid
p=pathlib.Path(os.environ['KNOWN_GOOD']); p.parent.mkdir(parents=True,exist_ok=True); t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
printf 'PRODUCTION_KNOWN_GOOD=%s\n' "${known_good}"
