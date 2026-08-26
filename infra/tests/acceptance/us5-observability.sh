#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
evidence="${CI_EVIDENCE_DIR:-${repo_root}/infra/evidence/runtime/us5-observability}"
mkdir -p "${evidence}"
bash "${repo_root}/infra/tests/contract/test-detection-rule.sh" | tee "${evidence}/rule-validation.txt"
bash "${repo_root}/infra/tests/security/test-observability-redaction.sh" | tee "${evidence}/redaction.txt"
bash "${repo_root}/infra/tests/integration/test-observability-isolation.sh" | tee "${evidence}/isolation.txt"

python - "${repo_root}/infra/observability/grafana/provisioning/alerting/policies.yaml" "${evidence}/routing-fixture.json" <<'PY'
import json,pathlib,sys,yaml
d=yaml.safe_load(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')); matchers=d['policies'][0]['routes'][0]['object_matchers']
def routed(labels):
 for key,op,value in matchers:
  if op=='=' and labels.get(key)!=value:return False
  if op=='!=' and labels.get(key)==value:return False
 return True
cases={'approved_anomaly':routed({'managed_by':'infra','approval_status':'approved','notify':'mattermost','domain':'host'}),'ci_status':routed({'managed_by':'infra','approval_status':'approved','notify':'mattermost','domain':'ci'}),'unapproved':routed({'managed_by':'infra','approval_status':'draft','notify':'mattermost','domain':'runtime'})}
assert cases=={'approved_anomaly':True,'ci_status':False,'unapproved':False}
pathlib.Path(sys.argv[2]).write_text(json.dumps({'routing':cases,'dedupeGroup':['alertname','environment','service','severity'],'sendResolved':True,'liveMattermostDelivery':'SERVER_GATE'},indent=2)+'\n',encoding='utf-8')
PY

if [[ "${RUN_MATTERMOST_E2E:-0}" == 1 ]]; then
  : "${MATTERMOST_WEBHOOK_URL:?}"
  echo 'Live contact-point delivery must be triggered from Grafana Test after provisioning.' >&2
  exit 75
fi
echo "PASS: static canary routing/redaction/isolation evidence written; live Mattermost delivery remains SERVER_GATE"
