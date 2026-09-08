#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
content_id=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

cat >"${tmp}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
host=''
if [[ "${1:-}" == --host ]]; then host="$2"; shift 2; fi
case "${host}:$1:$2" in
  source:image:inspect) printf '%s\n' "${FAKE_CONTENT_ID}" ;;
  source:image:save) printf 'image-stream' ;;
  target:image:load) cat >/dev/null; printf 'loaded\n' >"${FAKE_LOADED}" ;;
  target:image:inspect) [[ -f "${FAKE_LOADED}" ]] && printf '%s\n' "${FAKE_CONTENT_ID}" ;;
  *) echo "unexpected docker call: host=${host} $*" >&2; exit 64 ;;
esac
SH
chmod +x "${tmp}/docker"

export DOCKER_BIN="${tmp}/docker" BUILD_DOCKER_HOST=source DEPLOY_DOCKER_HOST=target
export IMAGE_REF=festa-back:0123456789abcdef0123456789abcdef01234567 CONTENT_ID="${content_id}"
export FAKE_CONTENT_ID="${content_id}" FAKE_LOADED="${tmp}/loaded"
bash "${repo_root}/infra/deploy/scripts/transfer-local-image.sh"
[[ -f "${FAKE_LOADED}" ]]

rm -f "${FAKE_LOADED}"
export CONTENT_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if bash "${repo_root}/infra/deploy/scripts/transfer-local-image.sh" >/dev/null 2>&1; then
  echo 'source image content mismatch was accepted' >&2; exit 1
fi
[[ ! -f "${FAKE_LOADED}" ]]
echo 'PASS: rootless image transfer preserves immutable content ID'
