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
cleanup() {
  rm -f "${download}" "${headers:-}"
  [[ -z "${staging:-}" ]] || rm -rf -- "${staging}"
  if [[ -n "${WEBGL_EVIDENCE_PATH:-}" ]]; then
    mkdir -p "$(dirname "${WEBGL_EVIDENCE_PATH}")"
    printf '{"releaseId":"%s","artifactSha256":"%s","target":"%s","status":"%s","finishedAt":"%s"}\n' \
      "${release_id}" "${expected_sha}" "${WEBGL_PUBLIC_BASE_URL}" "${status}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${WEBGL_EVIDENCE_PATH}"
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

old_target=
if [[ -L "${root}/current" ]]; then
  old_target="$(readlink "${root}/current")"
  [[ "${old_target}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] \
    || { echo 'current points outside the managed WebGL releases' >&2; exit 68; }
elif [[ -e "${root}/current" ]]; then
  echo 'current must be a symbolic link' >&2; exit 68
fi

tmp_link="${root}/.current.${release_id}.$$"
ln -s "releases/${release_id}" "${tmp_link}"
mv -Tf "${tmp_link}" "${root}/current"

verify_http() {
  local path="$1" expected_type="$2" require_brotli="${3:-0}" expected_cache="$4" base="${WEBGL_PUBLIC_BASE_URL%/}"
  headers="$(mktemp "${root}/.webgl-headers.XXXXXX")"
  if ! curl --fail --silent --show-error --location --max-time "${WEBGL_VERIFY_TIMEOUT_SECONDS:-20}" \
    --dump-header "${headers}" --output /dev/null "${base}/${path}"; then return 1; fi
  grep -Eiq "^Content-Type:[[:space:]]*${expected_type}([[:space:]]*;|[[:space:]]*$)" "${headers}" || return 1
  grep -Fiq "Cache-Control: ${expected_cache}" "${headers}" || return 1
  if [[ "${require_brotli}" == 1 ]]; then
    grep -Eiq '^Content-Encoding:[[:space:]]*br[[:space:]]*$' "${headers}" || return 1
  fi
  rm -f "${headers}"; headers=
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
  echo "public WebGL verification failed; restoring ${old_target:-empty current}" >&2
  if [[ -n "${old_target}" ]]; then
    rollback_link="${root}/.rollback.${release_id}.$$"
    ln -s "${old_target}" "${rollback_link}"
    mv -Tf "${rollback_link}" "${root}/current"
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
echo "DEPLOYED_WEBGL_RELEASE: ${release_id} ${expected_sha}"
