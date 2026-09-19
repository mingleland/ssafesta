#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 1 ]] || { echo 'Usage: stage-production-webgl.sh <receipt.json>' >&2; exit 64; }
receipt="$1"
[[ -f "${receipt}" ]] || { echo "missing receipt: ${receipt}" >&2; exit 66; }
webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
prod_root="${webgl_root}/prod"
IFS=$'\t' read -r release_id expected_sha source_commit < <(
  python3 - "${receipt}" <<'PY'
import json,pathlib,sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
w=doc['webgl']
print(w['packageVersion'],w['artifactSha256'],w['sourceCommit'],sep='\t')
PY
)
release="${webgl_root}/releases/${release_id}"
[[ -d "${release}" && -f "${release}/.artifact-sha256" && -f "${release}/manifest.json" ]] || { echo "approved WebGL release is incomplete: ${release_id}" >&2; exit 66; }
actual_sha="$(<"${release}/.artifact-sha256")"
[[ "${actual_sha}" == "${expected_sha}" ]] || { echo "WebGL checksum mismatch" >&2; exit 65; }
python3 - "${release}/manifest.json" "${source_commit}" <<'PY'
import json,pathlib,sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
expected={'sourceCommit':sys.argv[2],'sourceBranch':'develop','dirty':False,'buildProfile':'release','apiEnvironment':'Prod','compression':'brotli+fallback'}
for k,v in expected.items():
    if doc.get(k)!=v: raise SystemExit(f'Production WebGL manifest mismatch for {k}')
PY
mkdir -p "${prod_root}"
tmp="${prod_root}/.candidate.${release_id}.$$"
ln -s "../releases/${release_id}" "${tmp}"
mv -Tf "${tmp}" "${prod_root}/candidate"
[[ "$(readlink -f "${prod_root}/candidate")" == "$(readlink -f "${release}")" ]] || { echo 'Production WebGL candidate resolution mismatch' >&2; exit 65; }
echo "STAGED_PRODUCTION_WEBGL: ${release_id} ${expected_sha}"
