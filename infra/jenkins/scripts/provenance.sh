#!/usr/bin/env bash
set -euo pipefail

: "${PROVENANCE_LOG:?}" "${RUN_ID:?}" "${RELEASE_MANIFEST_PATH:?}" "${VERIFICATION_RESULT_PATH:?}" "${DEPLOYMENT_RECORD_PATH:?}"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
python - "$(native "${PROVENANCE_LOG}")" "$(native "${RELEASE_MANIFEST_PATH}")" "$(native "${VERIFICATION_RESULT_PATH}")" "$(native "${DEPLOYMENT_RECORD_PATH}")" <<'PY'
import datetime,json,os,pathlib,re,sys
release=json.load(open(sys.argv[2],encoding='utf-8')); verification=json.load(open(sys.argv[3],encoding='utf-8')); deployment=json.load(open(sys.argv[4],encoding='utf-8'))
if not (release['releaseId']==verification['releaseId']==deployment['candidateReleaseId']): raise SystemExit('provenance release IDs do not match')
row={'schemaVersion':'1.0.0','recordedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),'runId':os.environ['RUN_ID'],'release':release,'verification':verification,'deployment':deployment}
encoded=json.dumps(row,separators=(',',':'))
for pattern in (r'(?i)authorization\s*[:=]\s*(bearer|basic)',r'https?://[^\s/]+/(hooks|webhooks)/',r'-----BEGIN .*PRIVATE KEY-----'):
 if re.search(pattern,encoded): raise SystemExit('sensitive value rejected from provenance')
p=pathlib.Path(sys.argv[1]); p.parent.mkdir(parents=True,exist_ok=True)
with p.open('a',encoding='utf-8',newline='\n') as f: f.write(encoded+'\n')
print(p)
PY
