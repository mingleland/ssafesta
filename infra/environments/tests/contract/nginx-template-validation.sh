#!/usr/bin/env bash
# nginx template 검증이 살아 있는 서버 상태를 건드리지 않는다는 계약 (T-174).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

validator="${repo_root}/infra/environments/scripts/validate-nginx-template.sh"

assert_file "${validator}"
bash -n "${validator}"
[[ -x "${validator}" ]] || fail 'template validator must be executable'

# root 로 `nginx -t -c` 를 돌리면 nginx 가 설정의 user 로 /var/lib/nginx 의 temp path 를 chown 한다.
# 스크래치로 돌리지 않으면 실서버 worker 가 요청 본문을 못 써서 POST 가 500 이 된다.
for directive in client_body_temp_path proxy_temp_path fastcgi_temp_path scgi_temp_path uwsgi_temp_path; do
  assert_contains "${validator}" "${directive} %s" "validator must redirect ${directive} to a scratch directory"
done
assert_contains "${validator}" 'NGINX_TEMPLATE_USER:-www-data' 'validator must keep the live worker user when running as root'
# 주석으로 위험을 설명하는 건 괜찮다 — 실제 지시자/명령 줄이 live temp root 를 가리키면 안 된다.
assert_not_contains "${validator}" '^[^#]*/var/lib/nginx' 'validator must never point a directive at the live temp root'

# 실제로 돌려서 계약을 확인한다. nginx 가 없는 환경에서는 건너뛴다.
if command -v nginx >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
  bash "${validator}" \
    "${repo_root}/infra/environments/nginx/sites/api.conf.template" \
    "${repo_root}/infra/environments/nginx/sites/demo.conf.template" \
    "${repo_root}/infra/environments/nginx/sites/prod.conf.template" >/dev/null
else
  printf 'SKIP: nginx/openssl unavailable — static contract only\n'
fi

pass 'nginx template validation runs without touching live server state'
