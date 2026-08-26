#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
cat >"${tmp}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >>"${FAKE_DOCKER_LOG}"
if [[ "$1 $2" == "image inspect" ]]; then echo 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; fi
if [[ "$1" == compose && "$*" == *" ps -q "* ]]; then service="${!#}"; cat "${FAKE_STATE}/${service}"; fi
if [[ "$1" == compose && "$*" == *" up -d "* ]]; then service="${!#}"; echo "${service}-container-2" >"${FAKE_STATE}/${service}"; fi
SH
chmod +x "${tmp}/docker"
mkdir -p "${tmp}/state"; for component in ai back front game; do echo "${component}-container-1" >"${tmp}/state/${component}"; done
export FAKE_DOCKER_LOG="${tmp}/calls.log" FAKE_STATE="${tmp}/state" DOCKER_BIN="${tmp}/docker"
declare -A before after
for component in ai back front game; do before[${component}]="$(${DOCKER_BIN} compose --project-name "festa-dev-${component}" ps -q "${component}")"; done
: >"${FAKE_DOCKER_LOG}"
CI_COMPONENT=back COMPOSE_FILE="${repo_root}/infra/deploy/compose/dev/back.compose.yaml" \
COMPOSE_PROJECT=festa-dev-back COMPOSE_SERVICE=back \
IMAGE_REF=back:0123456789abcdef0123456789abcdef01234567 \
CONTENT_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
bash "${repo_root}/infra/deploy/scripts/deploy-component.sh"
cp "${FAKE_DOCKER_LOG}" "${tmp}/deploy-calls.log"
for component in ai back front game; do after[${component}]="$(${DOCKER_BIN} compose --project-name "festa-dev-${component}" ps -q "${component}")"; done
[[ "${before[back]}" != "${after[back]}" ]] || { echo 'target container did not change' >&2; exit 1; }
for component in ai front game; do [[ "${before[${component}]}" == "${after[${component}]}" ]] || { echo "${component} container changed" >&2; exit 1; }; done
grep -q 'compose.*festa-dev-back.*up -d --no-deps.*back' "${tmp}/deploy-calls.log"
! grep -Eq 'festa-dev-(ai|front|game)|[[:space:]](ai|front|game)$' "${tmp}/deploy-calls.log" || { echo "non-target component touched" >&2; exit 1; }
echo "PASS: component deployment isolation"
