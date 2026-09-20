#!/usr/bin/env bash
set -euo pipefail
set +x

usage() { echo 'Usage: publish-webgl-release.sh ZIP RELEASE_ID' >&2; exit 64; }
[[ $# -eq 2 ]] || usage
archive="$1" release_id="$2"
[[ -f "${archive}" ]] || { echo "WebGL zip not found: ${archive}" >&2; exit 66; }
[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ && "${release_id}" != '.' && "${release_id}" != '..' ]] || usage
: "${GITLAB_PACKAGE_TOKEN:?GITLAB_PACKAGE_TOKEN is required}"
: "${JENKINS_USER:?JENKINS_USER is required}"
: "${JENKINS_API_TOKEN:?JENKINS_API_TOKEN is required}"

gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
package_name="${WEBGL_PACKAGE_NAME:-festa-webgl}"
jenkins_url="${JENKINS_URL:?JENKINS_URL is required}"
jenkins_job="${JENKINS_WEBGL_JOB:-festa-webgl-package-deploy}"
filename="festa-webgl-release-${release_id}.zip"
package_base="${gitlab_api%/}/projects/${project_id}/packages/generic/${package_name}/${release_id}"
sha256="$(sha256sum "${archive}" | awk '{print $1}')"
checksum="$(mktemp)"
trap 'rm -f "${checksum}"' EXIT
printf '%s  %s\n' "${sha256}" "${filename}" >"${checksum}"

"${PYTHON_BIN:-python}" - "${archive}" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    bad = archive.testzip()
    if bad:
        raise SystemExit(f'corrupt zip entry: {bad}')
    names = set(archive.namelist())
for required in ('index.html', 'manifest.json'):
    if required not in names:
        raise SystemExit(f'zip is missing {required}')
for required in ('Build/', 'TemplateData/'):
    if not any(name.startswith(required) for name in names):
        raise SystemExit(f'zip is missing {required}')
PY

curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
  --upload-file "${archive}" "${package_base}/${filename}"
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
  --upload-file "${checksum}" "${package_base}/${filename}.sha256"

curl --fail --silent --show-error --request POST \
  --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" \
  --data-urlencode "RELEASE_ID=${release_id}" \
  --data-urlencode "ARTIFACT_SHA256=${sha256}" \
  "${jenkins_url%/}/job/${jenkins_job}/buildWithParameters"
echo "PUBLISHED_WEBGL_RELEASE: ${release_id} ${sha256}"
