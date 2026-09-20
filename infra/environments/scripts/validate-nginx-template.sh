#!/usr/bin/env bash
# nginx site template 을 살아 있는 서버를 건드리지 않고 검증한다.
#
# `nginx -t -c <파일>` 을 root 로 돌리면 nginx 는 설정을 읽는 것으로 끝나지 않는다 — 설정의 `user` 로
# `/var/lib/nginx/{body,proxy,fastcgi,scgi,uwsgi}` 를 chown 한다. 검증용 설정에 `user` 가 없으면
# 컴파일 기본값(nobody)으로 바뀌고, 그 순간부터 실서버 worker(www-data)가 요청 본문을 임시 파일로
# 쓰지 못해 1m 이하 요청까지 413 도 아닌 500 이 된다. 2026-09-20 에 GitLab webhook(POST) 이
# 이 이유로 끊겼다 (T-174).
#
# 그래서 이 스크립트는 temp path 를 전부 스크래치 디렉터리로 돌리고, root 로 돌 때는 `user` 까지
# 실서버와 같게 박는다. 인증서도 없으면 스크래치에 self-signed 로 만들어 쓴다 — 실 키를 읽을
# 필요가 없어 일반 사용자로도 돌아간다.
#
# 사용:  infra/environments/scripts/validate-nginx-template.sh <template> [<template>...]
# 변수:  ROOT_DOMAIN, DEMO_WORLD_HOST, PRODUCTION_WORLD_HOST 는 미설정 시 검증용 기본값을 쓴다.
#        NGINX_ORIGIN_CERTIFICATE_FILE / NGINX_ORIGIN_PRIVATE_KEY_FILE 을 주면 그 값을 그대로 쓴다.
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <nginx-template> [...]" >&2; exit 64; }
command -v "${NGINX_BIN:-nginx}" >/dev/null 2>&1 || { echo "nginx is required" >&2; exit 69; }
command -v python3 >/dev/null 2>&1 || { echo "Python 3 is required" >&2; exit 69; }

scratch="$(mktemp -d)"
trap 'rm -rf "${scratch}"' EXIT

if [[ -z "${NGINX_ORIGIN_CERTIFICATE_FILE:-}" || -z "${NGINX_ORIGIN_PRIVATE_KEY_FILE:-}" ]]; then
  command -v openssl >/dev/null 2>&1 || { echo "openssl is required when no origin certificate is supplied" >&2; exit 69; }
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=nginx-template-validation \
    -keyout "${scratch}/origin.key" -out "${scratch}/origin.pem" >/dev/null 2>&1
  NGINX_ORIGIN_CERTIFICATE_FILE="${scratch}/origin.pem"
  NGINX_ORIGIN_PRIVATE_KEY_FILE="${scratch}/origin.key"
fi

export ROOT_DOMAIN="${ROOT_DOMAIN:-example.test}"
export DEMO_WORLD_HOST="${DEMO_WORLD_HOST:-demo.${ROOT_DOMAIN}}"
export PRODUCTION_WORLD_HOST="${PRODUCTION_WORLD_HOST:-world.${ROOT_DOMAIN}}"
export NGINX_ORIGIN_CERTIFICATE_FILE NGINX_ORIGIN_PRIVATE_KEY_FILE

index=0
for template in "$@"; do
  [[ -f "${template}" ]] || { echo "missing template: ${template}" >&2; exit 66; }
  index=$((index + 1))
python3 - "${template}" "${scratch}/site-${index}.conf" <<'PY'
import os, pathlib, re, sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
text = re.sub(r"\$\{([A-Z0-9_]+)\}", lambda m: os.environ.get(m.group(1), m.group(0)), text)
if "${" in text:
    unresolved = sorted(set(re.findall(r"\$\{[A-Z0-9_]+\}", text)))
    raise SystemExit("unresolved template variable(s): " + ", ".join(unresolved))

# nginx -t 는 listen 소켓을 실제로 연다 — 80/443 그대로 두면 비root 는 bind 에서 막히고
# root 는 살아 있는 서버와 같은 포트를 건드린다. 검증에 필요한 건 문법·지시자라 포트만 옮긴다.
text = re.sub(r"(?m)^(\s*listen\s+)(\d+)", lambda m: m.group(1) + str(int(m.group(2)) + 20000), text)

pathlib.Path(sys.argv[2]).write_text(text, encoding="utf-8")
PY
done

# temp path 를 전부 스크래치로 돌린다 — 이 검증이 /var/lib/nginx 소유권을 건드릴 수 없게 하는 핵심이다.
{
  [[ "$(id -u)" -eq 0 ]] && printf 'user %s;\n' "${NGINX_TEMPLATE_USER:-www-data}"
  printf 'pid %s/nginx.pid;\n' "${scratch}"
  printf 'error_log %s/error.log;\n' "${scratch}"
  printf 'events {}\n'
  printf 'http {\n'
  printf '    access_log off;\n'
  for dir in body proxy fastcgi scgi uwsgi; do
    mkdir -p "${scratch}/${dir}"
  done
  printf '    client_body_temp_path %s/body;\n' "${scratch}"
  printf '    proxy_temp_path %s/proxy;\n' "${scratch}"
  printf '    fastcgi_temp_path %s/fastcgi;\n' "${scratch}"
  printf '    scgi_temp_path %s/scgi;\n' "${scratch}"
  printf '    uwsgi_temp_path %s/uwsgi;\n' "${scratch}"
  for conf in "${scratch}"/site-*.conf; do
    printf '    include %s;\n' "${conf}"
  done
  printf '}\n'
} > "${scratch}/nginx.conf"

"${NGINX_BIN:-nginx}" -t -c "${scratch}/nginx.conf" -p "${scratch}"
