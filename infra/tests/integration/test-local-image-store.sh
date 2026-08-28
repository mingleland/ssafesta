#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
cat >"${tmp}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_DOCKER_STATE}"
if [[ "$1 $2" == "image inspect" ]]; then [[ -f "${state}" ]] || exit 1; cat "${state}"; exit 0; fi
if [[ "$1" == "build" ]]; then printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' >"${state}"; exit 0; fi
exit 2
SH
chmod +x "${tmp}/docker"; touch "${tmp}/Dockerfile"
export DOCKER_BIN="${tmp}/docker" FAKE_DOCKER_STATE="${tmp}/state"
export CI_COMPONENT=back CI_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567
export IMAGE_NAME=festa-back DOCKERFILE="${tmp}/Dockerfile" BUILD_CONTEXT="${tmp}" CI_ARTIFACT_DIR="${tmp}/out"
bash "${repo_root}/infra/deploy/scripts/package-local-image.sh"
grep -q 'sha256:aaaaaaaa' "${tmp}/out/image-metadata.json"
export EXPECTED_CONTENT_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if bash "${repo_root}/infra/deploy/scripts/package-local-image.sh" >/dev/null 2>&1; then
  echo "existing immutable tag mismatch was accepted" >&2; exit 1
fi
echo "PASS: local image identity and immutable tag"
