#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
compose="${repo_root}/infra/observability/compose.yaml"

python - "${compose}" <<'PY'
import pathlib,sys,yaml
d=yaml.safe_load(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
services=d['services']
assert set(services)=={'alloy','alloy-cadvisor','loki','prometheus','grafana'}
for name, service in services.items():
    assert not service.get('depends_on'), f'{name} must not gate on another observability service'
    assert 'healthcheck' in service and 'restart' in service
    if name != 'grafana': assert service.get('networks')==['observability-private']
assert services['grafana']['networks']==['observability-private','observability-egress']
assert d['networks']['observability-private']['internal'] is True
assert services['grafana']['ports'][0].startswith('127.0.0.1:')
assert all('ports' not in services[name] for name in ('alloy','alloy-cadvisor','loki','prometheus'))
assert services['alloy-cadvisor']['profiles']==['container-metrics']
assert services['alloy-cadvisor']['privileged'] is True
PY

probe="${repo_root}/infra/observability/scripts/isolation-probe.sh"
for failed in alloy loki prometheus grafana; do
  OBSERVABILITY_FAILED_SERVICE="${failed}" APP_PROBE_COMMAND=true CI_PROBE_COMMAND=true bash "${probe}"
done
echo 'PASS: each observability failure remains outside app and CI success criteria'
