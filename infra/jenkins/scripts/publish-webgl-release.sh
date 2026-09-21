#!/usr/bin/env bash
# WebGL release zip 을 Generic Package Registry 에 게시한다 (festa-webgl/<8sha>).
#
# Batch 2 계약: 같은 version 이 이미 있고 SHA 가 같으면 WEBGL_RELEASE_EXISTS(0) — 다시 올리지 않는다.
# 같은 version 인데 SHA 가 다르면 65 — source SHA 하나에 바이너리가 둘이 되는 일은 조용히 일어나면 안 된다.
# .json sidecar 에는 실행 provenance(Jenkins job/build)를 둔다; zip 안의 ci-provenance.json 은 source-stable 값만 가진다.
set -euo pipefail
set +x

usage() { echo 'Usage: publish-webgl-release.sh ZIP RELEASE_ID [--no-trigger]' >&2; exit 64; }
[[ $# -ge 2 ]] || usage
archive="$1" release_id="$2"; shift 2
trigger=1
while [[ $# -gt 0 ]]; do case "$1" in --no-trigger) trigger=0; shift ;; *) usage ;; esac; done
[[ -f "${archive}" ]] || { echo "WebGL zip not found: ${archive}" >&2; exit 66; }
[[ "${release_id}" =~ ^[0-9a-f]{8}$ ]] || { echo 'RELEASE_ID must be the 8-char source SHA (canonical festa-webgl version)' >&2; usage; }
: "${GITLAB_PACKAGE_TOKEN:?GITLAB_PACKAGE_TOKEN is required}"
if (( trigger )); then
  : "${JENKINS_USER:?JENKINS_USER is required}"
  : "${JENKINS_API_TOKEN:?JENKINS_API_TOKEN is required}"
fi
python_bin="${PYTHON_BIN:-python3}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
package_name="${WEBGL_PACKAGE_NAME:-festa-webgl}"
jenkins_job="${JENKINS_WEBGL_JOB:-festa-webgl-package-deploy}"
filename="festa-webgl-release-${release_id}.zip"
metadata_filename="festa-webgl-release-${release_id}.json"
package_base="${gitlab_api%/}/projects/${project_id}/packages/generic/${package_name}/${release_id}"
sha256="$(sha256sum "${archive}" | awk '{print $1}')"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT HUP INT TERM
checksum="${work}/${filename}.sha256"
printf '%s  %s\n' "${sha256}" "${filename}" >"${checksum}"

# 구조·lineage 검사는 validator 하나로 통일한다. 출력: WEBGL_ARCHIVE_OK <sourceCommit> <sha256>
read -r _ source_commit _ < <(PYTHON_BIN="${python_bin}" bash "${script_dir}/validate-webgl-archive.sh" "${archive}")
[[ "${source_commit:0:8}" == "${release_id}" ]] || { echo "RELEASE_ID ${release_id} is not the prefix of manifest sourceCommit ${source_commit}" >&2; exit 65; }

# 같은 version 이 이미 있는가 — sidecar 하나만 읽어 판단한다 (재업로드 금지, 다른 바이너리면 hard fail).
existing="${work}/existing.sha256"
http_code="$(curl --silent --show-error --location --output "${existing}" --write-out '%{http_code}' --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" "${package_base}/${filename}.sha256")"
case "${http_code}" in
  200)
    read -r existing_sha existing_name _ <"${existing}"
    [[ "${existing_name:-}" == "${filename}" ]] || { echo "existing festa-webgl/${release_id} checksum sidecar names another file: ${existing_name:-?}" >&2; exit 65; }
    [[ "${existing_sha:-}" == "${sha256}" ]] || { echo "PACKAGE_IDENTITY_COLLISION: festa-webgl/${release_id} already holds ${existing_sha:-?}, local zip is ${sha256}" >&2; exit 65; }
    echo "WEBGL_RELEASE_EXISTS: ${release_id} ${sha256}"
    exit 0 ;;
  404) ;;
  *) echo "WebGL package lookup failed: HTTP ${http_code}" >&2; exit 69 ;;
esac

RELEASE_ID="${release_id}" SOURCE_COMMIT="${source_commit}" SHA256="${sha256}" PACKAGE_URL="${package_base}/${filename}" \
  "${python_bin}" - "${work}/${metadata_filename}" <<'PY'
import datetime, json, os, pathlib, sys
env = os.environ
doc = {
  'schemaVersion': '1.0.0', 'packageName': 'festa-webgl', 'packageVersion': env['RELEASE_ID'],
  'sourceCommit': env['SOURCE_COMMIT'], 'sourceBranch': 'develop', 'artifactSha256': env['SHA256'], 'packageUrl': env['PACKAGE_URL'],
  'ciProvider': 'jenkins' if env.get('JENKINS_JOB') else 'manual',
  'jenkinsJob': env.get('JENKINS_JOB') or None, 'jenkinsBuildNumber': env.get('JENKINS_BUILD_NUMBER') or None,
  'jenkinsBuildUrl': env.get('JENKINS_BUILD_URL') or None, 'builderClass': env.get('BUILDER_CLASS') or 'unity-6000.0.78f1',
  'publishedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
}
pathlib.Path(sys.argv[1]).write_text(json.dumps(doc, indent=2) + '\n', encoding='utf-8')
PY

curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
  --upload-file "${archive}" "${package_base}/${filename}"
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
  --upload-file "${checksum}" "${package_base}/${filename}.sha256"
curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_PACKAGE_TOKEN}" \
  --upload-file "${work}/${metadata_filename}" "${package_base}/${metadata_filename}"

if (( trigger )); then
  # HISTORICAL/FALLBACK 경로: 수동 게시 뒤 festa-webgl-package-deploy 를 깨운다. develop 파이프라인은 --no-trigger 로 부르고 직접 배포한다.
  jenkins_url="${JENKINS_URL:?JENKINS_URL is required}"
  curl --fail --silent --show-error --request POST \
    --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" \
    --data-urlencode "RELEASE_ID=${release_id}" \
    --data-urlencode "ARTIFACT_SHA256=${sha256}" \
    "${jenkins_url%/}/job/${jenkins_job}/buildWithParameters"
fi
echo "PUBLISHED_WEBGL_RELEASE: ${release_id} ${sha256}"
