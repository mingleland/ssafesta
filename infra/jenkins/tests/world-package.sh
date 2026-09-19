#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

publisher="${repo_root}/infra/jenkins/scripts/publish-world-release.sh"
loader="${repo_root}/infra/deploy/scripts/load-production-world.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

mkdir -p \
  "${work}/bin" \
  "${work}/remote"

source_commit='0123456789abcdef0123456789abcdef01234567'
release_id="${source_commit:0:8}"
image_ref="festa-game:${source_commit}"

# ------------------------------------------------------------
# Build a minimal Docker-save-shaped fixture.
# Docker image ID is sha256(config JSON bytes).
# ------------------------------------------------------------

SOURCE_COMMIT="${source_commit}" \
IMAGE_REF="${image_ref}" \
WORK="${work}" \
python3 <<'PY_FIXTURE'
import hashlib
import io
import json
import os
import pathlib
import tarfile

work = pathlib.Path(os.environ["WORK"])
image_ref = os.environ["IMAGE_REF"]

config = json.dumps(
    {
        "architecture": "amd64",
        "os": "linux",
        "config": {
            "Labels": {
                "org.ssafy-festa.component": "game",
                "org.ssafy-festa.source-commit": os.environ["SOURCE_COMMIT"],
            }
        },
    },
    separators=(",", ":"),
).encode()

content_id = "sha256:" + hashlib.sha256(config).hexdigest()
(work / "content-id").write_text(content_id + "\n")

manifest = json.dumps(
    [
        {
            "Config": "config.json",
            "RepoTags": [image_ref],
            "Layers": [],
        }
    ],
    separators=(",", ":"),
).encode()

archive_path = work / "fixture.tar"

with tarfile.open(archive_path, "w") as archive:
    for name, body in (
        ("manifest.json", manifest),
        ("config.json", config),
    ):
        info = tarfile.TarInfo(name)
        info.size = len(body)
        info.mtime = 0
        archive.addfile(info, io.BytesIO(body))
PY_FIXTURE

content_id="$(<"${work}/content-id")"


# ------------------------------------------------------------
# Fake docker
# ------------------------------------------------------------

cat >"${work}/bin/docker" <<'SH_DOCKER'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG}"

case "${1:-} ${2:-}" in
  'image inspect')
    printf '%s\n' "${FAKE_CONTENT_ID}"
    ;;

  'image save')
    [[ "${3:-}" == '--output' ]] || exit 64
    cp "${FAKE_ARCHIVE_SOURCE}" "${4:?}"
    ;;

  'image load')
    [[ "${3:-}" == '--input' ]] || exit 64
    [[ -f "${4:?}" ]] || exit 66
    printf 'Loaded image: fixture\n'
    ;;

  *)
    echo "unexpected docker invocation: $*" >&2
    exit 64
    ;;
esac
SH_DOCKER

chmod +x "${work}/bin/docker"


# ------------------------------------------------------------
# Fake curl implementing Generic Package GET/upload/download
# ------------------------------------------------------------

cat >"${work}/bin/curl" <<'SH_CURL'
#!/usr/bin/env bash
set -euo pipefail

output=''
write_out=''
upload=''
fail_mode=0
url=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      output="${2:-}"
      shift 2
      ;;
    --write-out)
      write_out="${2:-}"
      shift 2
      ;;
    --upload-file)
      upload="${2:-}"
      shift 2
      ;;
    --header)
      shift 2
      ;;
    --fail|--fail-with-body)
      fail_mode=1
      shift
      ;;
    --silent|--show-error|--location)
      shift
      ;;
    -*)
      echo "unsupported fake curl option: $1" >&2
      exit 64
      ;;
    *)
      url="$1"
      shift
      ;;
  esac
done

[[ -n "${url}" ]] || exit 64

relative="${url#*/packages/generic/}"

[[ "${relative}" != "${url}" ]] || {
  echo "unexpected URL: ${url}" >&2
  exit 64
}

target="${FAKE_REMOTE_ROOT}/${relative}"
code=200

if [[ -n "${upload}" ]]; then
  mkdir -p "$(dirname "${target}")"
  cp "${upload}" "${target}"
  code=201
else
  if [[ -f "${target}" ]]; then
    [[ -z "${output}" ]] || cp "${target}" "${output}"
    code=200
  else
    [[ -z "${output}" ]] || : >"${output}"
    code=404
  fi
fi

if [[ -n "${write_out}" ]]; then
  printf '%s' "${code}"
fi

if (( fail_mode )) && (( code >= 400 )); then
  exit 22
fi
SH_CURL

chmod +x "${work}/bin/curl"

export PATH="${work}/bin:${PATH}"
export FAKE_CONTENT_ID="${content_id}"
export FAKE_ARCHIVE_SOURCE="${work}/fixture.tar"
export FAKE_DOCKER_LOG="${work}/docker.log"
export FAKE_REMOTE_ROOT="${work}/remote"

export GITLAB_PACKAGE_TOKEN='fixture-package-token'
export GITLAB_DEPLOY_TOKEN='fixture-deploy-token'

package_url="https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/festa-world/${release_id}/festa-world-release-${release_id}.tar"


# ------------------------------------------------------------
# Publish exact existing image
# ------------------------------------------------------------

publish_output="$(
  DOCKER_BIN=docker \
  PYTHON_BIN=python3 \
    "${publisher}" \
      --source-commit "${source_commit}" \
      --image-ref "${image_ref}" \
      --content-id "${content_id}"
)"

[[ "${publish_output}" == PUBLISHED_WORLD_RELEASE:* ]] \
  || fail "first World publication did not publish"

remote_root="${work}/remote/festa-world/${release_id}"

[[ -f "${remote_root}/festa-world-release-${release_id}.tar" ]] \
  || fail "World archive was not uploaded"

[[ -f "${remote_root}/festa-world-release-${release_id}.tar.sha256" ]] \
  || fail "World checksum sidecar was not uploaded"

[[ -f "${remote_root}/festa-world-release-${release_id}.json" ]] \
  || fail "World provenance metadata was not uploaded"


# ------------------------------------------------------------
# Same immutable version + identity is idempotent.
# It must NOT save/upload another archive.
# ------------------------------------------------------------

: >"${work}/docker.log"

publish_again="$(
  DOCKER_BIN=docker \
  PYTHON_BIN=python3 \
    "${publisher}" \
      --source-commit "${source_commit}" \
      --image-ref "${image_ref}" \
      --content-id "${content_id}"
)"

[[ "${publish_again}" == WORLD_RELEASE_EXISTS:* ]] \
  || fail "existing identical World package was not idempotent"

if grep -q '^image save ' "${work}/docker.log"; then
  fail "idempotent World publication saved a second archive"
fi


# ------------------------------------------------------------
# Same package version with another recorded identity must fail.
# ------------------------------------------------------------

cp \
  "${remote_root}/festa-world-release-${release_id}.json" \
  "${work}/metadata.good.json"

python3 - "${remote_root}/festa-world-release-${release_id}.json" <<'PY_BAD_METADATA'
import json
import pathlib
import sys

p = pathlib.Path(sys.argv[1])
d = json.loads(p.read_text())
d["imageContentId"] = "sha256:" + "9" * 64
p.write_text(json.dumps(d, indent=2) + "\n")
PY_BAD_METADATA

if DOCKER_BIN=docker PYTHON_BIN=python3 \
  "${publisher}" \
    --source-commit "${source_commit}" \
    --image-ref "${image_ref}" \
    --content-id "${content_id}" \
    >/dev/null 2>&1
then
  fail "World publisher accepted an existing version with another identity"
fi

cp \
  "${work}/metadata.good.json" \
  "${remote_root}/festa-world-release-${release_id}.json"


# ------------------------------------------------------------
# Production loader: checksum + archive identity + docker load.
# ------------------------------------------------------------

archive_sha="$(
  sha256sum \
    "${remote_root}/festa-world-release-${release_id}.tar" \
    | awk '{print $1}'
)"

: >"${work}/docker.log"

load_output="$(
  DOCKER_BIN=docker \
  PYTHON_BIN=python3 \
    "${loader}" \
      --release-id "${release_id}" \
      --sha256 "${archive_sha}" \
      --package-url "${package_url}" \
      --image-ref "${image_ref}" \
      --content-id "${content_id}"
)"

[[ "${load_output}" == LOADED_WORLD_RELEASE:* ]] \
  || fail "valid World archive was rejected"

grep -q '^image load --input ' "${work}/docker.log" \
  || fail "valid World archive was not loaded"


# ------------------------------------------------------------
# Wrong archive checksum must fail BEFORE docker load.
# ------------------------------------------------------------

: >"${work}/docker.log"

if DOCKER_BIN=docker PYTHON_BIN=python3 \
  "${loader}" \
    --release-id "${release_id}" \
    --sha256 "$(printf '0%.0s' {1..64})" \
    --package-url "${package_url}" \
    --image-ref "${image_ref}" \
    --content-id "${content_id}" \
    >/dev/null 2>&1
then
  fail "World loader accepted a bad archive checksum"
fi

if grep -q '^image load ' "${work}/docker.log"; then
  fail "checksum-failed World archive reached docker load"
fi


# ------------------------------------------------------------
# Wrong image content identity must also fail BEFORE docker load.
# ------------------------------------------------------------

: >"${work}/docker.log"

if DOCKER_BIN=docker PYTHON_BIN=python3 \
  "${loader}" \
    --release-id "${release_id}" \
    --sha256 "${archive_sha}" \
    --package-url "${package_url}" \
    --image-ref "${image_ref}" \
    --content-id "sha256:$(printf '9%.0s' {1..64})" \
    >/dev/null 2>&1
then
  fail "World loader accepted a different image content ID"
fi

if grep -q '^image load ' "${work}/docker.log"; then
  fail "identity-failed World archive reached docker load"
fi

echo 'PASS: World package preserves exact Demo Docker image identity without rebuilding and Production rejects altered artifacts before docker load'
