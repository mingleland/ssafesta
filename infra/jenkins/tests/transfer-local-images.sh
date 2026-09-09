#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
sha='0123456789abcdef0123456789abcdef01234567'
content_id='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
bundle='transfer-smoke'

cat >"${work}/manifest.json" <<JSON
{"scm":{"commit":"${sha}"},"components":[{"name":"back","storageMode":"local-docker","sourceCommit":"${sha}","imageRef":"festa-back:${sha}","contentId":"${content_id}"}]}
JSON

cat >"${work}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "$1 $2" in
  'image inspect') printf '%s\n' "${FAKE_CONTENT_ID}" ;;
  'image save')
    shift 2
    [[ "$1" == --output ]]
    : >"$2"
    ;;
  'image load') [[ "$4" == *.images.tar ]] ;;
  *) echo "unexpected docker invocation: $*" >&2; exit 64 ;;
esac
SH
chmod +x "${work}/docker"

PATH="${work}:${PATH}" FAKE_CONTENT_ID="${content_id}" \
  bash "${repo_root}/infra/jenkins/scripts/transfer-local-images.sh" \
    --export --bundle "${bundle}" --transfer-dir "${work}/transfer" --manifest "${work}/manifest.json" >/dev/null

[[ -f "${work}/transfer/${bundle}.images.tar" ]]
[[ -f "${work}/transfer/${bundle}.release-manifest.json" ]]

PATH="${work}:${PATH}" FAKE_CONTENT_ID="${content_id}" \
  bash "${repo_root}/infra/jenkins/scripts/transfer-local-images.sh" \
    --import --bundle "${bundle}" --transfer-dir "${work}/transfer" >/dev/null

[[ ! -e "${work}/transfer/${bundle}.images.tar" ]]
[[ ! -e "${work}/transfer/${bundle}.release-manifest.json" ]]

echo 'PASS: selected local images transfer with their manifest and retain content identity'
