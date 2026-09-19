#!/usr/bin/env bash
set -euo pipefail
set +x

usage() {
  echo 'Usage: load-production-world.sh --release-id ID --sha256 HEX --package-url URL --image-ref REF --content-id sha256:HEX' >&2
  exit 64
}

release_id=''
expected_sha=''
package_url=''
image_ref=''
expected_content_id=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-id)
      release_id="${2:-}"
      shift 2
      ;;
    --sha256)
      expected_sha="${2:-}"
      shift 2
      ;;
    --package-url)
      package_url="${2:-}"
      shift 2
      ;;
    --image-ref)
      image_ref="${2:-}"
      shift 2
      ;;
    --content-id)
      expected_content_id="${2:-}"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ "${release_id}" =~ ^[0-9a-f]{8}$ ]] || usage
[[ "${expected_sha}" =~ ^[0-9a-f]{64}$ ]] || usage
[[ -n "${package_url}" ]] || usage
[[ -n "${image_ref}" ]] || usage
[[ "${expected_content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || usage

expected_filename="festa-world-release-${release_id}.tar"

[[ "${package_url}" == */packages/generic/festa-world/"${release_id}"/"${expected_filename}" ]] || {
  echo 'Production World package URL does not match release ID' >&2
  exit 65
}

: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"

docker_bin="${DOCKER_BIN:-docker}"
python_bin="${PYTHON_BIN:-python3}"

command -v "${python_bin}" >/dev/null 2>&1 \
  || { echo 'Python 3 is required' >&2; exit 69; }

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT HUP INT TERM

archive="${work}/${expected_filename}"

curl \
  --fail \
  --silent \
  --show-error \
  --location \
  --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" \
  --output "${archive}" \
  "${package_url}"

actual_sha="$(sha256sum "${archive}" | awk '{print $1}')"

[[ "${actual_sha}" == "${expected_sha}" ]] || {
  echo "Production World archive checksum mismatch: expected=${expected_sha} actual=${actual_sha}" >&2
  exit 65
}

# Verify image ID from the Docker config inside the archive BEFORE docker load.
# A wrong package therefore cannot overwrite a local tag before being rejected.
"${python_bin}" - \
  "${archive}" \
  "${image_ref}" \
  "${expected_content_id}" <<'PY_ARCHIVE'
import hashlib
import json
import pathlib
import tarfile
import sys

archive_path = pathlib.Path(sys.argv[1])
expected_ref = sys.argv[2]
expected_id = sys.argv[3]

with tarfile.open(archive_path, "r:*") as archive:
    try:
        manifest_member = archive.getmember("manifest.json")
    except KeyError:
        raise SystemExit("Production World archive has no manifest.json")

    manifest_stream = archive.extractfile(manifest_member)
    if manifest_stream is None:
        raise SystemExit("Production World archive manifest is unreadable")

    manifest = json.load(manifest_stream)

    matches = [
        item
        for item in manifest
        if expected_ref in (item.get("RepoTags") or [])
    ]

    if len(matches) != 1:
        raise SystemExit(
            "Production World archive does not contain exactly one expected image"
        )

    config_name = matches[0].get("Config")
    if not isinstance(config_name, str) or not config_name:
        raise SystemExit("Production World archive image has no config")

    try:
        config_member = archive.getmember(config_name)
    except KeyError:
        raise SystemExit("Production World archive config is missing")

    config_stream = archive.extractfile(config_member)
    if config_stream is None:
        raise SystemExit("Production World archive config is unreadable")

    actual_id = (
        "sha256:"
        + hashlib.sha256(config_stream.read()).hexdigest()
    )

    if actual_id != expected_id:
        raise SystemExit(
            "Production World archive content mismatch: "
            f"expected={expected_id} actual={actual_id}"
        )
PY_ARCHIVE

"${docker_bin}" image load \
  --input "${archive}" \
  >/dev/null

loaded_content_id="$(
  "${docker_bin}" image inspect \
    --format '{{.Id}}' \
    "${image_ref}"
)"

[[ "${loaded_content_id}" == "${expected_content_id}" ]] || {
  echo "loaded Production World image identity mismatch: expected=${expected_content_id} actual=${loaded_content_id}" >&2
  exit 65
}

echo "LOADED_WORLD_RELEASE: ${release_id} ${actual_sha} ${loaded_content_id}"
