#!/usr/bin/env bash
# dev 배포 테스트가 실제 Docker 없이 동일한 컨테이너 상태와 릴리스 입력을 공유한다.
set -euo pipefail

create_dev_deploy_fixture() {
  FIXTURE_ROOT="$(mktemp -d)"
  export FIXTURE_ROOT FAKE_DOCKER_STATE="${FIXTURE_ROOT}/state" FAKE_DOCKER_LOG="${FIXTURE_ROOT}/docker.log"
  mkdir -p "${FAKE_DOCKER_STATE}"
  : >"${FAKE_DOCKER_LOG}"

  local project service
  for service in ai back front game; do
    printf '%s|%s|0\n' "festa-dev-${service}-1" "sha256:$(printf 'a%.0s' {1..64})" \
      >"${FAKE_DOCKER_STATE}/festa-dev__${service}"
  done
  for service in postgres redis; do
    printf '%s|%s|0\n' "festa-data-${service}-1" "sha256:$(printf 'd%.0s' {1..64})" \
      >"${FAKE_DOCKER_STATE}/festa-data__${service}"
  done
  printf '%s|%s|0\n' festa-demo-front-1 "sha256:$(printf 'e%.0s' {1..64})" \
    >"${FAKE_DOCKER_STATE}/festa-demo__front"

  cat >"${FIXTURE_ROOT}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG}"
candidate="sha256:$(printf 'b%.0s' {1..64})"
previous="sha256:$(printf 'a%.0s' {1..64})"

if [[ "${1:-} ${2:-}" == 'image inspect' ]]; then
  case "${!#}" in
    *:old) printf '%s\n' "${previous}" ;;
    *) printf '%s\n' "${candidate}" ;;
  esac
  exit 0
fi
if [[ "${1:-}" == inspect ]]; then
  name="${!#}"
  row="$(grep -l "^${name}|" "${FAKE_DOCKER_STATE}"/* | head -n1)"
  IFS='|' read -r container image restart release <"${row}"
  printf '%s|%s|%s\n' "${image}" "${restart}" "${release:-old-release}"
  exit 0
fi
if [[ "${1:-} ${2:-}" == 'network inspect' ]]; then exit 0; fi
if [[ "${1:-}" == ps ]]; then
  project=''
  for arg in "$@"; do
    [[ "${arg}" == label=com.docker.compose.project=* ]] && project="${arg##*=}"
  done
  for row in "${FAKE_DOCKER_STATE}/${project}"__*; do
    [[ -f "${row}" ]] || continue
    service="${row##*__}"
    printf '%s|%s\n' "$(cut -d'|' -f1 "${row}")" "${service}"
  done
  exit 0
fi
if [[ "${1:-}" == compose ]]; then
  project=''
  previous=''
  for arg in "$@"; do
    [[ "${previous}" == --project-name ]] && project="${arg}"
    previous="${arg}"
  done
  if [[ "$*" == *' up -d --no-deps '* ]]; then
    service="${!#}"
    image="${candidate}"
    if [[ "${COMPONENT_IMAGE_REF:-}" == *:old || "${RELEASE_ID:-}" == dev-previous-release ]]; then
      image="${previous}"
    fi
    printf '%s|%s|0|%s\n' "${project}-${service}-2" "${image}" "${RELEASE_ID}" >"${FAKE_DOCKER_STATE}/${project}__${service}"
  fi
  exit 0
fi
exit 0
SH
  chmod +x "${FIXTURE_ROOT}/docker"
  export DOCKER_BIN="${FIXTURE_ROOT}/docker"

  : >"${FIXTURE_ROOT}/component.env"
  printf '%s\n' 'Zm91bmRhdGlvbi1vbmx5LWNvbm5lY3Rpb24tdG9rZW4tc2VjcmV0' >"${FIXTURE_ROOT}/connection-token"
  export COMPONENT_ENV_FILE="${FIXTURE_ROOT}/component.env"
  export CONNECTION_TOKEN_SECRET_FILE="${FIXTURE_ROOT}/connection-token"
  export INTERNAL_SPRING_TO_AI_TOKENS='[TEST-ONLY]'
  export INTERNAL_AI_TO_SPRING_TOKENS='[TEST-ONLY]'
  export INTERNAL_INFRA_TO_SPRING_TOKENS='[TEST-ONLY]'
  export PUBLIC_API_BASE_URL= PUBLIC_AI_API_BASE_URL= PUBLIC_AUTH_BASE_URL=https://api.example.test PUBLIC_UNITY_BUILD_BASE=/unity/
  export ROOT_DOMAIN=example.test FRESHNESS_ACTUAL_SHA=0123456789abcdef0123456789abcdef01234567
  export ENVIRONMENT_STATE_DIR="${FIXTURE_ROOT}/runtime"

  RELEASE_MANIFEST="${FIXTURE_ROOT}/release-manifest.json"
  export RELEASE_MANIFEST
  cat >"${RELEASE_MANIFEST}" <<'JSON'
{
  "schemaVersion": "1.0.0",
  "releaseId": "dev-0123456789abcdef0123456789abcdef01234567-1",
  "scm": {
    "provider": "gitlab",
    "repository": "group/project",
    "branch": "develop",
    "commit": "0123456789abcdef0123456789abcdef01234567"
  },
  "jenkins": {"job": "fixture", "buildNumber": 1},
  "components": [
    {"name":"ai","storageMode":"local-docker","imageRef":"festa-ai:new","contentId":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sourceCommit":"0123456789abcdef0123456789abcdef01234567"},
    {"name":"back","storageMode":"local-docker","imageRef":"festa-back:new","contentId":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sourceCommit":"0123456789abcdef0123456789abcdef01234567"},
    {"name":"front","storageMode":"local-docker","imageRef":"festa-front:new","contentId":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sourceCommit":"0123456789abcdef0123456789abcdef01234567"},
    {"name":"game","storageMode":"local-docker","imageRef":"festa-game:new","contentId":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sourceCommit":"0123456789abcdef0123456789abcdef01234567"}
  ],
  "rollbackSafety": {"classification":"SAFE","dataChange":"none","dbSchemaChanged":false,"secretOrConfigChanged":false},
  "createdAt": "2026-09-08T00:00:00Z"
}
JSON
}

destroy_dev_deploy_fixture() {
  [[ -n "${FIXTURE_ROOT:-}" ]] && rm -rf -- "${FIXTURE_ROOT}"
}
