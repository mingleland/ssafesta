#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
deploy="${repo_root}/infra/jenkins/scripts/deploy-webgl-release.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

export WEBGL_RELEASE_ROOT="${fixture}/webgl"
export ENVIRONMENT_STATE_DIR="${fixture}/state"
export WEBGL_PUBLIC_BASE_URL='https://demo.example.invalid/unity'
export WEBGL_RETENTION_COUNT=2
export GITLAB_DEPLOY_TOKEN='fixture-token'
export PATH="${fixture}/bin:${PATH}"
mkdir -p "${fixture}/bin" "${fixture}/packages"

cat >"${fixture}/bin/flock" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "${fixture}/bin/flock"

cat >"${fixture}/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output= headers= url= resolved=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    --dump-header) headers="$2"; shift 2 ;;
    --resolve) resolved=1; shift 2 ;;
    --header|--max-time|--max-filesize|--user-agent) shift 2 ;;
    --insecure|--fail|--silent|--show-error|--location) shift ;;
    *) url="$1"; shift ;;
  esac
done
if [[ "${url}" == package://* ]]; then
  cp "${FAKE_PACKAGE}" "${output}"
  exit 0
fi
# 진단용 요청은 헤더를 받지 않는다 — 차단 페이지 본문과 /cdn-cgi/trace 를 흉내 낸다 (S15P21A604-664).
if [[ -z "${headers}" ]]; then
  case "${url}" in
    */cdn-cgi/trace) printf 'fl=1f2\nh=demo.example.invalid\nip=203.0.113.7\ncolo=ICN\n'; exit 0 ;;
  esac
  if [[ "${EDGE_BLOCK:-0}" == 1 && -n "${output}" ]]; then
    printf '<!DOCTYPE html><html><head><title>Access denied | demo.example.invalid used Cloudflare to restrict access</title></head><body>error code: 1020</body></html>' >"${output}"
  fi
  exit 0
fi
[[ "${FAIL_HTTP:-0}" != 1 ]] || exit 22
# 엣지(Cloudflare)가 막는 상황. --resolve 가 붙은 요청 = 오리진 직접 확인이라 이 차단을 지나간다.
if [[ "${EDGE_BLOCK:-0}" == 1 && "${resolved}" != 1 ]]; then
  printf 'HTTP/2 403\r\nContent-Type: text/html\r\nserver: cloudflare\r\ncf-ray: fixture-ray-0001\r\n\r\n' >"${headers}"
  exit 0
fi
if [[ "${ORIGIN_BLOCK:-0}" == 1 && "${resolved}" == 1 ]]; then
  printf 'HTTP/2 502\r\nContent-Type: text/html\r\n\r\n' >"${headers}"
  exit 0
fi
case "${url}" in
  */manifest.json) type='application/json' ;;
  */index.html) type='text/html' ;;
  *.wasm.br|*.wasm.unityweb) type='application/wasm' ;;
  *.js.br|*.js.unityweb|*.js) type='application/javascript' ;;
  *.data.br|*.data.unityweb) type='application/octet-stream' ;;
  *) type='application/octet-stream' ;;
esac
printf 'HTTP/2 200\r\nContent-Type: %s\r\n' "${type}" >"${headers}"
case "${url}" in *.br|*.unityweb) printf 'Content-Encoding: br\r\n' >>"${headers}" ;; esac
case "${url}" in
  */manifest.json|*/index.html) printf 'Cache-Control: no-cache\r\n' >>"${headers}" ;;
  *) printf 'Cache-Control: public, max-age=31536000, immutable\r\n' >>"${headers}" ;;
esac
printf '\r\n' >>"${headers}"
SH
chmod +x "${fixture}/bin/curl"

make_package() {
  local release_id="$1" mode="${2:-valid}" output
  output="${fixture}/packages/${release_id}.zip"
  RELEASE_ID="${release_id}" MODE="${mode}" OUTPUT="${output}" "${PYTHON_BIN:-python}" - <<'PY'
import json, os, zipfile
release_id, mode, output = os.environ['RELEASE_ID'], os.environ['MODE'], os.environ['OUTPUT']
manifest = {
    'loaderUrl': f'Build/{release_id}.loader.js',
    'dataUrl': f'Build/{release_id}.data.br',
    'frameworkUrl': f'Build/{release_id}.framework.js.br',
    'codeUrl': f'Build/{release_id}.wasm.br',
    'sourceCommit': 'a' * 40,
    'sourceBranch': 'develop',
    'dirty': False,
    'buildProfile': 'release',
}
if mode == 'bad-manifest':
    manifest['codeUrl'] = 'Build/missing.wasm.br'
with zipfile.ZipFile(output, 'w') as archive:
    archive.writestr('index.html', '<!doctype html>')
    archive.writestr('manifest.json', json.dumps(manifest))
    archive.writestr('TemplateData/style.css', '')
    for path in (manifest['loaderUrl'], manifest['dataUrl'], manifest['frameworkUrl'], manifest['codeUrl']):
        if path != 'Build/missing.wasm.br':
            archive.writestr(path, release_id)
    if mode == 'traversal':
        archive.writestr('../escape.txt', 'blocked')
PY
  printf '%s' "${output}"
}

deploy_package() {
  local package="$1" release_id="$2" hash
  hash="$(sha256sum "${package}" | awk '{print $1}')"
  FAKE_PACKAGE="${package}" "${deploy}" --release-id "${release_id}" --sha256 "${hash}" --package-url "package://${release_id}"
}

old="$(make_package old00001)"
deploy_package "${old}" old00001
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/old00001' ]]
grep -Fq '"releaseId": "old00001"' "${ENVIRONMENT_STATE_DIR}/dev/batches/known-good/webgl.json"
# 릴리스 디렉터리는 웹 서버가 들어갈 수 있어야 한다. mktemp -d 가 만든 0700 을 그대로 두면
# nginx 가 403 Forbidden 을 돌려주고, 그게 엣지 차단처럼 보인다 (S15P21A604-665, GitLab #165).
[[ "$(stat -c '%a' "${WEBGL_RELEASE_ROOT}/releases/old00001")" == *5 ]]
[[ "$(stat -c '%a' "${WEBGL_RELEASE_ROOT}/releases/old00001/Build")" == *5 ]]
[[ "$(stat -c '%a' "${WEBGL_RELEASE_ROOT}/releases/old00001/index.html")" == *[4567] ]]
# 파일에까지 실행 비트를 뿌리면 안 된다 — a+rX 의 X 는 디렉터리에만 붙는다.
[[ "$(stat -c '%a' "${WEBGL_RELEASE_ROOT}/releases/old00001/index.html")" != *[1357] ]]
# 이미 0700 으로 깔려 있던 릴리스를 다시 돌리면 고쳐 줘야 한다 — 아니면 같은 403 이 영영 반복된다.
chmod 700 "${WEBGL_RELEASE_ROOT}/releases/old00001"
deploy_package "${old}" old00001
[[ "$(stat -c '%a' "${WEBGL_RELEASE_ROOT}/releases/old00001")" == *5 ]]
[[ "$(find "${WEBGL_RELEASE_ROOT}/releases" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 1 ]]

if FAKE_PACKAGE="${old}" "${deploy}" --release-id badsha01 --sha256 "$(printf '0%.0s' {1..64})" --package-url package://badsha01 >/dev/null 2>&1; then
  echo 'bad SHA was accepted' >&2; exit 1
fi
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/old00001' ]]

traversal="$(make_package escape01 traversal)"
if deploy_package "${traversal}" escape01 >/dev/null 2>&1; then echo 'traversal zip was accepted' >&2; exit 1; fi
[[ ! -e "${WEBGL_RELEASE_ROOT}/escape.txt" && ! -e "${fixture}/escape.txt" ]]

bad_manifest="$(make_package badman01 bad-manifest)"
if deploy_package "${bad_manifest}" badman01 >/dev/null 2>&1; then echo 'bad manifest was accepted' >&2; exit 1; fi

candidate="$(make_package new00001)"
export WEBGL_EVIDENCE_PATH="${fixture}/webgl-deployment.json"
# 재시도가 들어갔으므로 테스트에서는 대기 없이 한 번 더만 시도하게 한다 (S15P21A604-656).
export WEBGL_VERIFY_RETRIES=2 WEBGL_VERIFY_RETRY_DELAY_SECONDS=0
export FAIL_HTTP=1
if deploy_package "${candidate}" new00001 >/dev/null 2>&1; then echo 'failed HTTP verification was accepted' >&2; exit 1; fi
unset FAIL_HTTP
grep -Fq '"status":"FAILED"' "${WEBGL_EVIDENCE_PATH}"
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/old00001' ]]
grep -Fq '"releaseId": "old00001"' "${ENVIRONMENT_STATE_DIR}/dev/batches/known-good/webgl.json"

# 엣지가 검증 요청만 막고 오리진은 멀쩡한 경우 — 릴리스를 되돌리지 않고 살려야 한다 (S15P21A604-656).
export EDGE_BLOCK=1
deploy_package "${candidate}" new00001 >/dev/null 2>"${fixture}/edge-block.log"
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/new00001' ]]
grep -Fq '"status":"SUCCEEDED"' "${WEBGL_EVIDENCE_PATH}"
grep -Fq '"verifiedVia":"origin"' "${WEBGL_EVIDENCE_PATH}"
grep -Fq '"releaseId": "new00001"' "${ENVIRONMENT_STATE_DIR}/dev/batches/known-good/webgl.json"
# 막혔을 때 인프라가 바로 쓸 수 있는 두 가지가 로그에 남아야 한다 (S15P21A604-664, GitLab #165).
grep -Fq 'used Cloudflare to restrict access' "${fixture}/edge-block.log"
grep -Fq 'error code: 1020' "${fixture}/edge-block.log"
grep -Fq 'ip=203.0.113.7' "${fixture}/edge-block.log"

# 엣지도 오리진도 막히면 그건 진짜 실패다 — 그때는 되돌린다.
rollback_probe="$(make_package new00003)"
export ORIGIN_BLOCK=1
if deploy_package "${rollback_probe}" new00003 >/dev/null 2>&1; then echo 'origin failure was accepted' >&2; exit 1; fi
unset EDGE_BLOCK ORIGIN_BLOCK
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/new00001' ]]

deploy_package "${candidate}" new00001
grep -Fq '"status":"SUCCEEDED"' "${WEBGL_EVIDENCE_PATH}"
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/new00001' ]]
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/previous")" == 'releases/old00001' ]]

third="$(make_package new00002)"
deploy_package "${third}" new00002
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/current")" == 'releases/new00002' ]]
[[ "$(readlink "${WEBGL_RELEASE_ROOT}/previous")" == 'releases/new00001' ]]
[[ ! -e "${WEBGL_RELEASE_ROOT}/releases/old00001" ]]

cat >"${fixture}/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
url="${!#}"
printf '%s\n' "${url}" >>"${CURL_CALLS}"
if [[ "${FAIL_CHECKSUM_UPLOAD:-0}" == 1 && "${url}" == *.sha256 ]]; then exit 22; fi
SH
chmod +x "${fixture}/bin/curl"
export GITLAB_PACKAGE_TOKEN='fixture-write-token'
export JENKINS_USER='fixture-publisher'
export JENKINS_API_TOKEN='fixture-api-token'
export JENKINS_URL='https://ci.example.invalid'
export CURL_CALLS="${fixture}/publish-success.calls"
"${repo_root}/infra/jenkins/scripts/publish-webgl-release.sh" "${third}" publish01 >/dev/null
[[ "$(wc -l <"${CURL_CALLS}")" -eq 3 ]]
tail -n 1 "${CURL_CALLS}" | grep -Fq '/job/festa-webgl-package-deploy/buildWithParameters'

export CURL_CALLS="${fixture}/publish-failure.calls"
export FAIL_CHECKSUM_UPLOAD=1
if "${repo_root}/infra/jenkins/scripts/publish-webgl-release.sh" "${third}" publish02 >/dev/null 2>&1; then
  echo 'publisher accepted a failed checksum upload' >&2; exit 1
fi
unset FAIL_CHECKSUM_UPLOAD
[[ "$(wc -l <"${CURL_CALLS}")" -eq 2 ]]
! grep -q 'buildWithParameters' "${CURL_CALLS}"

echo 'PASS: WebGL package publish ordering, deployment validation, idempotency, rollback and retention'
