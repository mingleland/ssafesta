#!/usr/bin/env bash
set -euo pipefail
docker_bin="${DOCKER_BIN:-docker}"
: "${CI_COMPONENT:?}" "${CI_COMMIT_SHA:?}" "${IMAGE_NAME:?}" "${DOCKERFILE:?}" "${BUILD_CONTEXT:?}" "${CI_ARTIFACT_DIR:?}"
[[ "${CI_COMPONENT}" =~ ^(ai|back|front|game)$ ]] || { echo 'invalid component' >&2; exit 64; }
[[ "${CI_COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]] || { echo 'full lowercase commit SHA required' >&2; exit 64; }
[[ -f "${DOCKERFILE}" ]] || { echo "Dockerfile not found: ${DOCKERFILE}" >&2; exit 66; }
[[ -d "${BUILD_CONTEXT}" ]] || { echo "build context not found: ${BUILD_CONTEXT}" >&2; exit 66; }
image_ref="${IMAGE_NAME}:${CI_COMMIT_SHA}"
existing="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}" 2>/dev/null || true)"
if [[ -z "${existing}" ]]; then
  "${docker_bin}" build --pull=false --tag "${image_ref}" \
    --label org.ssafy-festa.managed=true \
    --label "org.ssafy-festa.component=${CI_COMPONENT}" \
    --label "org.ssafy-festa.source-commit=${CI_COMMIT_SHA}" \
    --file "${DOCKERFILE}" "${BUILD_CONTEXT}"
fi
content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
[[ "${content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'Docker returned invalid image ID' >&2; exit 70; }
if [[ -n "${EXPECTED_CONTENT_ID:-}" && "${EXPECTED_CONTENT_ID}" != "${content_id}" ]]; then
  echo "immutable tag content mismatch for ${image_ref}" >&2; exit 65
fi
mkdir -p "${CI_ARTIFACT_DIR}"
metadata_path="${CI_ARTIFACT_DIR}/image-metadata.json"
python_metadata_path="${metadata_path}"
if command -v cygpath >/dev/null 2>&1; then python_metadata_path="$(cygpath -w "${metadata_path}")"; fi
python - "${python_metadata_path}" <<PY
import json,pathlib
p=pathlib.Path(r'''${python_metadata_path}''')
p.write_text(json.dumps({'schemaVersion':'1.0.0','component':'${CI_COMPONENT}','sourceCommit':'${CI_COMMIT_SHA}','storageMode':'local-docker','imageRef':'${image_ref}','contentId':'${content_id}'},indent=2)+'\n',encoding='utf-8')
PY
printf '%s %s\n' "${image_ref}" "${content_id}"
