#!/usr/bin/env bash
set -euo pipefail
set +x

usage() { echo 'Usage: deploy-webgl-release.sh --release-id ID --sha256 HEX --package-url URL' >&2; exit 64; }
release_id= expected_sha= package_url=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-id) release_id="${2:-}"; shift 2 ;;
    --sha256) expected_sha="${2:-}"; shift 2 ;;
    --package-url) package_url="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ && "${release_id}" != '.' && "${release_id}" != '..' ]] || usage
expected_sha="${expected_sha,,}"
[[ "${expected_sha}" =~ ^[0-9a-f]{64}$ && -n "${package_url}" ]] || usage
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
: "${WEBGL_PUBLIC_BASE_URL:?WEBGL_PUBLIC_BASE_URL is required}"

root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
[[ "${root}" == /* && "${root}" != / ]] || { echo 'WEBGL_RELEASE_ROOT must be a non-root absolute path' >&2; exit 64; }
releases="${root}/releases"
candidate="${releases}/${release_id}"
retention="${WEBGL_RETENTION_COUNT:-5}"
[[ "${retention}" =~ ^[1-9][0-9]*$ ]] || { echo 'WEBGL_RETENTION_COUNT must be a positive integer' >&2; exit 64; }
mkdir -p "${releases}"
exec 9>"${root}/.deploy.lock"
flock -x 9

download="$(mktemp "${root}/.webgl-download.XXXXXX")"
staging=
headers=
status=FAILED
# 엣지(Cloudflare)가 검증 요청을 막아 오리진 직접 확인으로 합격시킨 적이 있는가.
# 증거에 남겨야 "이 릴리스는 공개 경로로는 확인하지 못했다" 를 나중에도 알 수 있다.
edge_blocked=0
cleanup() {
  rm -f "${download}" "${headers:-}"
  [[ -z "${staging:-}" ]] || rm -rf -- "${staging}"
  if [[ -n "${WEBGL_EVIDENCE_PATH:-}" ]]; then
    mkdir -p "$(dirname "${WEBGL_EVIDENCE_PATH}")"
    printf '{"releaseId":"%s","artifactSha256":"%s","target":"%s","status":"%s","verifiedVia":"%s","finishedAt":"%s"}\n' \
      "${release_id}" "${expected_sha}" "${WEBGL_PUBLIC_BASE_URL}" "${status}" \
      "$([[ "${edge_blocked}" == 1 ]] && echo origin || echo edge)" \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${WEBGL_EVIDENCE_PATH}"
  fi
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

curl --fail --silent --show-error \
  --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" \
  --output "${download}" "${package_url}"
actual_sha="$(sha256sum "${download}" | awk '{print $1}')"
[[ "${actual_sha}" == "${expected_sha}" ]] || { echo "artifact SHA-256 mismatch for ${release_id}" >&2; exit 65; }

if [[ -e "${candidate}" ]]; then
  [[ -d "${candidate}" && ! -L "${candidate}" && -f "${candidate}/.artifact-sha256" ]] \
    || { echo "existing release path is invalid: ${release_id}" >&2; exit 66; }
  [[ "$(<"${candidate}/.artifact-sha256")" == "${expected_sha}" ]] \
    || { echo "release ID already exists with another artifact: ${release_id}" >&2; exit 67; }
else
  staging="$(mktemp -d "${releases}/.${release_id}.staging.XXXXXX")"
  "${PYTHON_BIN:-python}" - "${download}" "${staging}" "${WEBGL_MAX_UNCOMPRESSED_BYTES:-4294967296}" <<'PY'
import json, pathlib, re, stat, sys, zipfile

archive_path, root_path, max_bytes = sys.argv[1:]
root = pathlib.Path(root_path).resolve()
with zipfile.ZipFile(archive_path) as archive:
    infos = archive.infolist()
    if sum(info.file_size for info in infos) > int(max_bytes):
        raise SystemExit('WebGL archive exceeds uncompressed size limit')
    seen = set()
    for info in infos:
        raw = info.filename.replace('\\', '/')
        parts = raw.rstrip('/').split('/')
        if (not raw or raw.startswith('/') or re.match(r'^[A-Za-z]:', raw)
                or any(part in ('', '.', '..') for part in parts)
                or any(ord(char) < 32 for char in raw)
                or stat.S_ISLNK(info.external_attr >> 16) or info.flag_bits & 1):
            raise SystemExit(f'unsafe zip entry: {info.filename!r}')
        normalized = '/'.join(parts)
        if normalized in seen:
            raise SystemExit(f'duplicate zip entry: {info.filename!r}')
        seen.add(normalized)
        target = root.joinpath(*parts)
        if root not in target.resolve().parents and target.resolve() != root:
            raise SystemExit(f'zip entry escapes release root: {info.filename!r}')
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open('wb') as destination:
                while chunk := source.read(1024 * 1024):
                    destination.write(chunk)

for required in ('index.html', 'manifest.json'):
    if not root.joinpath(required).is_file():
        raise SystemExit(f'missing WebGL file: {required}')
for required in ('Build', 'TemplateData'):
    if not root.joinpath(required).is_dir():
        raise SystemExit(f'missing WebGL directory: {required}')

try:
    manifest = json.loads(root.joinpath('manifest.json').read_text(encoding='utf-8'))
except (UnicodeDecodeError, json.JSONDecodeError) as error:
    raise SystemExit(f'invalid manifest.json: {error}')
for key in ('loaderUrl', 'dataUrl', 'frameworkUrl', 'codeUrl'):
    value = manifest.get(key) if isinstance(manifest, dict) else None
    if not isinstance(value, str) or not re.fullmatch(r'Build/[A-Za-z0-9._/-]+', value) or '..' in value.split('/'):
        raise SystemExit(f'invalid manifest path: {key}')
    target = root.joinpath(*value.split('/')).resolve()
    if root not in target.parents or not target.is_file():
        raise SystemExit(f'manifest target is missing: {key}')
PY
  printf '%s\n' "${expected_sha}" >"${staging}/.artifact-sha256"
  printf '{"releaseId":"%s","artifactSha256":"%s","packageUrl":"%s","installedAt":"%s"}\n' \
    "${release_id}" "${expected_sha}" "${package_url}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${staging}/.release.json"
  mv "${staging}" "${candidate}"
  staging=
fi

manifest_output="$("${PYTHON_BIN:-python}" - "${candidate}" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1]).resolve()
for required in ('index.html', 'manifest.json'):
    if not root.joinpath(required).is_file(): raise SystemExit(f'missing WebGL file: {required}')
for required in ('Build', 'TemplateData'):
    if not root.joinpath(required).is_dir(): raise SystemExit(f'missing WebGL directory: {required}')
manifest = json.loads(root.joinpath('manifest.json').read_text(encoding='utf-8'))
for key in ('loaderUrl', 'dataUrl', 'frameworkUrl', 'codeUrl'):
    value = manifest.get(key) if isinstance(manifest, dict) else None
    if not isinstance(value, str) or not re.fullmatch(r'Build/[A-Za-z0-9._/-]+', value) or '..' in value.split('/'):
        raise SystemExit(f'invalid manifest path: {key}')
    target = root.joinpath(*value.split('/')).resolve()
    if root not in target.parents or not target.is_file(): raise SystemExit(f'manifest target is missing: {key}')
    print(value)
PY
)"
mapfile -t manifest_paths <<<"${manifest_output}"

# 이 스크립트가 관리하기 전에 손으로 배포한 흔적이 남아 있을 수 있다(2026-09-10 수동 배포).
# 그때의 current 는 releases/<id> 상대 심링크가 아니라 절대 경로 심링크이거나 실체 디렉터리다.
# 그대로 중단하면 첫 자동 배포가 영영 못 들어간다 — 옆으로 치워 두고 진행한다.
#
# **치운 것을 반드시 기억해 둔다.** 처음 이 분기를 넣었을 때 old_target 을 비워 버려서,
# 검증이 실패하자 롤백이 되돌릴 대상을 잃고 current 를 지웠다 — demo 의 /unity/ 가 404 로
# 내려앉았다(2026-09-11 06:01 UTC, build #3). 롤백은 legacy_current 를 제자리로 되돌린다.
old_target=
legacy_current=
if [[ -L "${root}/current" ]]; then
  old_target="$(readlink "${root}/current")"
  if [[ ! "${old_target}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
    legacy_current="${root}/current.legacy.$(date -u +%Y%m%d%H%M%S)"
    echo "current points outside the managed WebGL releases (${old_target}) — moving it to $(basename "${legacy_current}")" >&2
    mv -T "${root}/current" "${legacy_current}"
    old_target=
  fi
elif [[ -e "${root}/current" ]]; then
  legacy_current="${root}/current.legacy.$(date -u +%Y%m%d%H%M%S)"
  echo "current is not a symbolic link — moving it to $(basename "${legacy_current}")" >&2
  mv -T "${root}/current" "${legacy_current}"
else
  # current 가 아예 없다. 앞선 실패로 치워만 두고 못 되돌린 것이 남아 있으면 그것을 롤백 대상으로 삼는다 —
  # 그래야 이번 배포가 또 실패해도 서비스가 되살아난다. 가장 최근 것 하나만 본다.
  for candidate in "${root}"/current.legacy.*; do
    [[ -e "${candidate}" ]] && legacy_current="${candidate}"
  done
  [[ -n "${legacy_current}" ]] \
    && echo "no current; will fall back to $(basename "${legacy_current}") if this release fails verification" >&2
fi

tmp_link="${root}/.current.${release_id}.$$"
ln -s "releases/${release_id}" "${tmp_link}"
mv -Tf "${tmp_link}" "${root}/current"

# 검증이 실패하면 **왜 실패했는지 로그에 남긴다.**
# 전에는 `curl --fail` 이 조용히 종료해 "403" 이라는 숫자만 남았고, 그게 파일 문제인지
# 앞단(WAF·프록시) 차단인지 구분할 수 없어 사람이 서버에 들어가 다시 확인해야 했다
# (2026-09-11 #165). 상태줄과 몇 개 헤더만 찍어도 그 한 번이 사라진다.
# 토큰이 실리는 요청이 아니므로 헤더를 남겨도 비밀이 새지 않는다.
log_verify_failure() {
  local url="$1" file="$2"
  local status; status="$(head -n 1 "${file}" 2>/dev/null | tr -d '\r')"
  echo "  verify failed: ${url}" >&2
  [[ -n "${status}" ]] && echo "    ${status}" >&2
  # 앞단이 누구인지 드러내는 헤더만 추린다 — cf-ray 가 있으면 Cloudflare 단에서 끊긴 것이다.
  grep -Ei '^(server|cf-ray|cf-cache-status|via|x-cache|content-type|cache-control|content-encoding):' "${file}" 2>/dev/null \
    | sed 's/^/    /' >&2 || true
}

# 한 번 받아 보고 판정한다. **실패를 두 종류로 나누는 것이 요점이다.**
#   1 = 일시 실패(네트워크 오류·403·408·425·429·5xx) → 다시 물어볼 가치가 있다
#   2 = 배포 결함(2xx 인데 Content-Type·Cache-Control·Content-Encoding 이 계약과 다르다) → 몇 번을 물어도 같다
# 둘을 같은 실패로 묶으면 앞단이 잠깐 막은 것 때문에 멀쩡한 릴리스를 되돌리게 된다 (2026-09-11 #165).
# 검증 요청이 쓰는 User-Agent. 기본 `curl/8.x` 는 엣지의 봇 규칙이 흔히 집는 값이고,
# 우리는 사람이 브라우저로 여는 것과 **같은 파일이 같은 헤더로 나오는가**를 보려는 것이지
# 봇으로 구분되려는 것이 아니다. 엣지 정책이 바뀌면 이 한 줄만 바꾸면 된다.
VERIFY_USER_AGENT="${WEBGL_VERIFY_USER_AGENT:-Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36}"

request_and_check() {
  local url="$1" expected_type="$2" require_brotli="$3" expected_cache="$4" file="$5"
  : >"${file}"
  curl --silent --show-error --location --max-time "${WEBGL_VERIFY_TIMEOUT_SECONDS:-20}" \
    --user-agent "${VERIFY_USER_AGENT}" \
    --dump-header "${file}" --output /dev/null "${url}" || return 1
  # --fail 을 뺐으므로 상태코드를 직접 본다 (--fail 은 본문·헤더를 버려 진단을 못 남긴다).
  local status_line; status_line="$(head -n 1 "${file}" 2>/dev/null | tr -d '\r')"
  if ! [[ "${status_line}" =~ ^HTTP/[0-9.]+[[:space:]]+2[0-9][0-9] ]]; then
    [[ "${status_line}" =~ [[:space:]](403|408|425|429|5[0-9][0-9])([[:space:]]|$) ]] && return 1
    return 2
  fi
  grep -Eiq "^Content-Type:[[:space:]]*${expected_type}([[:space:]]*;|[[:space:]]*$)" "${file}" || return 2
  grep -Fiq "Cache-Control: ${expected_cache}" "${file}" || return 2
  if [[ "${require_brotli}" == 1 ]]; then
    grep -Eiq '^Content-Encoding:[[:space:]]*br[[:space:]]*$' "${file}" || return 2
  fi
  return 0
}

# 엣지(Cloudflare)를 건너뛰고 **이 호스트의 nginx 에 직접** 같은 경로를 묻는다.
# 이름은 그대로 두고 주소만 바꾸는 --resolve 라야 server_name·location 규칙을 그대로 탄다.
# 오리진 인증서는 엣지 전용(origin cert)이라 공개 체인으로 검증되지 않으므로 --insecure 다 —
# 여기서 보는 것은 신원이 아니라 **우리가 방금 올린 파일이 제대로 서빙되는가** 하나다.
verify_origin() {
  local path="$1" expected_type="$2" require_brotli="$3" expected_cache="$4"
  [[ "${WEBGL_ORIGIN_VERIFY:-1}" == 1 ]] || return 1
  local base="${WEBGL_PUBLIC_BASE_URL%/}" addr="${WEBGL_ORIGIN_ADDRESS:-127.0.0.1}" scheme host port
  scheme="${base%%://*}"; host="${base#*://}"; host="${host%%/*}"; host="${host%%:*}"
  port=443; [[ "${scheme}" == http ]] && port=80
  local file; file="$(mktemp "${root}/.webgl-origin.XXXXXX")"
  local rc=1 curl_rc=0
  if curl --silent --show-error --location --insecure \
      --max-time "${WEBGL_VERIFY_TIMEOUT_SECONDS:-20}" --resolve "${host}:${port}:${addr}" \
      --user-agent "${VERIFY_USER_AGENT}" \
      --dump-header "${file}" --output /dev/null "${base}/${path}" || { curl_rc=$?; false; }; then
    head -n 1 "${file}" | grep -Eq '^HTTP/[0-9.]+ 2[0-9][0-9]' \
      && grep -Eiq "^Content-Type:[[:space:]]*${expected_type}([[:space:]]*;|[[:space:]]*$)" "${file}" \
      && grep -Fiq "Cache-Control: ${expected_cache}" "${file}" \
      && { [[ "${require_brotli}" != 1 ]] || grep -Eiq '^Content-Encoding:[[:space:]]*br[[:space:]]*$' "${file}"; } \
      && rc=0
  fi
  if [[ ${rc} -ne 0 ]]; then
    # curl 7 = 연결 자체가 안 됐다. 배포물 문제가 아니라 **오리진 주소가 틀린 것**이므로 그렇게 말한다 —
    # 이 에이전트가 웹 서버와 다른 호스트면 WEBGL_ORIGIN_ADDRESS 로 실제 주소를 줘야 한다.
    if [[ ${curl_rc} -eq 7 ]]; then
      echo "  오리진 확인 불가: ${addr}:${port} 에 연결되지 않는다 — 이 에이전트가 웹 서버가 아니면 WEBGL_ORIGIN_ADDRESS 를 지정해야 한다" >&2
    else
      echo "  origin check also failed: ${base}/${path} via ${addr}" >&2
      log_verify_failure "origin ${base}/${path}" "${file}"
    fi
  fi
  rm -f "${file}"
  return ${rc}
}

verify_http() {
  local path="$1" expected_type="$2" require_brotli="${3:-0}" expected_cache="$4" base="${WEBGL_PUBLIC_BASE_URL%/}"
  local url="${base}/${path}" attempts="${WEBGL_VERIFY_RETRIES:-3}" delay="${WEBGL_VERIFY_RETRY_DELAY_SECONDS:-3}"
  headers="$(mktemp "${root}/.webgl-headers.XXXXXX")"
  local rc=1 i
  for ((i = 1; i <= attempts; i++)); do
    request_and_check "${url}" "${expected_type}" "${require_brotli}" "${expected_cache}" "${headers}"; rc=$?
    [[ ${rc} -ne 1 ]] && break
    if [[ ${i} -lt ${attempts} ]]; then
      echo "  verify 일시 실패 — ${delay}s 뒤 재시도 ${i}/$((attempts - 1)): ${url}" >&2
      sleep "${delay}"
    fi
  done
  if [[ ${rc} -eq 0 ]]; then rm -f "${headers}"; headers=; return 0; fi

  log_verify_failure "${url}" "${headers}"
  # cf-ray 가 찍혔다면 우리 nginx 가 아니라 엣지가 끊은 것이다. 그럴 때만 오리진에 직접 물어
  # **배포물 자체**를 판정한다 — 엣지가 검증자를 막았다는 이유로 멀쩡한 릴리스를 되돌리지 않기 위해서다.
  if [[ ${rc} -eq 1 ]] && grep -qi '^cf-ray:' "${headers}" \
     && verify_origin "${path}" "${expected_type}" "${require_brotli}" "${expected_cache}"; then
    edge_blocked=1
    echo "  엣지가 검증 요청을 막았지만 오리진은 정상이다 — 배포물로는 합격 처리: ${path}" >&2
    rm -f "${headers}"; headers=
    return 0
  fi
  rm -f "${headers}"; headers=
  return 1
}

verify_release() {
  verify_http manifest.json application/json 0 no-cache || return 1
  verify_http index.html text/html 0 no-cache || return 1
  local path type compressed
  for path in "${manifest_paths[@]}"; do
    compressed=0; [[ "${path}" == *.br || "${path}" == *.unityweb ]] && compressed=1
    case "${path%.br}" in
      *.wasm|*.wasm.unityweb) type='application/wasm' ;;
      *.js|*.js.unityweb) type='application/javascript' ;;
      *.data|*.data.unityweb) type='application/octet-stream' ;;
      *) type='application/octet-stream' ;;
    esac
    verify_http "${path}" "${type}" "${compressed}" 'public, max-age=31536000, immutable' || return 1
  done
}

if ! verify_release; then
  echo "public WebGL verification failed; restoring ${old_target:-${legacy_current:-empty current}}" >&2
  if [[ -n "${old_target}" ]]; then
    rollback_link="${root}/.rollback.${release_id}.$$"
    ln -s "${old_target}" "${rollback_link}"
    mv -Tf "${rollback_link}" "${root}/current"
  elif [[ -n "${legacy_current}" && -e "${legacy_current}" ]]; then
    # 손으로 배포해 둔 것을 옆으로 치워 왔다면 **그것을 제자리로 되돌린다.**
    # 여기서 그냥 지우면 서비스가 통째로 내려간다 — 실제로 그렇게 내려갔다.
    rm -f "${root}/current"
    mv -T "${legacy_current}" "${root}/current"
  else
    rm -f "${root}/current"
  fi
  exit 69
fi

if [[ -n "${old_target}" && "${old_target}" != "releases/${release_id}" ]]; then
  previous_link="${root}/.previous.${release_id}.$$"
  ln -s "${old_target}" "${previous_link}"
  mv -Tf "${previous_link}" "${root}/previous"
fi

protected="${release_id}"
[[ -L "${root}/previous" ]] && protected+=" $(basename "$(readlink "${root}/previous")")"
mapfile -t release_dirs < <(find "${releases}" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -printf '%T@ %f\n' | sort -rn | awk '{print $2}')
kept=0
for name in "${release_dirs[@]}"; do
  [[ "${name}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || continue
  if [[ " ${protected} " == *" ${name} "* ]]; then continue; fi
  kept=$((kept + 1))
  if (( kept > retention - 2 )); then rm -rf -- "${releases:?}/${name}"; fi
done

status=SUCCEEDED
if [[ "${edge_blocked}" == 1 ]]; then
  echo "WARNING: 공개 경로 검증이 엣지에서 막혀 오리진 직접 확인으로 대체했다 —" \
       "배포물은 정상이지만 엣지(Cloudflare)가 이 에이전트를 막는지 인프라에 확인이 필요하다." >&2
fi
echo "DEPLOYED_WEBGL_RELEASE: ${release_id} ${expected_sha}"
