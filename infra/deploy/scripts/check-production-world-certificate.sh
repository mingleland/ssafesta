#!/usr/bin/env bash
# 운영 월드 WSS 인증서가 PRODUCTION_WORLD_HOST 를 덮고 곧 만료되지 않는지, maintenance(503) 전환 전에 확인한다 (T-295).
set -euo pipefail
set +x
: "${PRODUCTION_WORLD_HOST:?PRODUCTION_WORLD_HOST is required}"
: "${PRODUCTION_WORLD_CERTIFICATE_FILE:?PRODUCTION_WORLD_CERTIFICATE_FILE is required}"
: "${PRODUCTION_WORLD_PRIVATE_KEY_FILE:?PRODUCTION_WORLD_PRIVATE_KEY_FILE is required}"
docker_bin="${DOCKER_BIN:-docker}"
fail(){ echo "Production World certificate check failed: $*" >&2; exit 65; }
# activate-production-release.sh 와 같은 방식으로 호스트 파일을 본다 — 배포 에이전트에는 nginx·sudo 가 없다.
privileged(){
  if [[ "${PRODUCTION_USE_SUDO:-1}" == 1 ]]; then
    if ! command -v "${NGINX_BIN:-nginx}" >/dev/null 2>&1 && command -v "${docker_bin}" >/dev/null 2>&1; then
      "${docker_bin}" run --rm --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n "$@"
    else
      "${SUDO_BIN:-sudo}" -n "$@"
    fi
  else
    "$@"
  fi
}
openssl_bin="${OPENSSL_BIN:-openssl}"
privileged test -r "${PRODUCTION_WORLD_PRIVATE_KEY_FILE}" || fail "private key is not readable: ${PRODUCTION_WORLD_PRIVATE_KEY_FILE}"
privileged "${openssl_bin}" x509 -in "${PRODUCTION_WORLD_CERTIFICATE_FILE}" -noout -checkend 604800 >/dev/null \
  || fail "certificate is missing or expires within 7 days: ${PRODUCTION_WORLD_CERTIFICATE_FILE}"
host_check="$(privileged "${openssl_bin}" x509 -in "${PRODUCTION_WORLD_CERTIFICATE_FILE}" -noout -checkhost "${PRODUCTION_WORLD_HOST}")" \
  || fail "certificate could not be read: ${PRODUCTION_WORLD_CERTIFICATE_FILE}"
[[ "${host_check}" == *"does match certificate"* ]] || fail "certificate does not cover ${PRODUCTION_WORLD_HOST}"
printf 'PRODUCTION_WORLD_CERTIFICATE=PASS host=%s file=%s\n' "${PRODUCTION_WORLD_HOST}" "${PRODUCTION_WORLD_CERTIFICATE_FILE}"
