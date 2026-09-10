#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --export|--import --bundle <safe-id> --transfer-dir <dir> [--manifest <path>]" >&2
}

mode=''
bundle=''
transfer_dir=''
manifest=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --export|--import) mode="${1#--}"; shift ;;
    --bundle) bundle="${2:-}"; shift 2 ;;
    --transfer-dir) transfer_dir="${2:-}"; shift 2 ;;
    --manifest) manifest="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done

[[ -n "${mode}" && -n "${bundle}" && -n "${transfer_dir}" ]] || { usage; exit 64; }
[[ "${bundle}" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo 'bundle must be a safe identifier' >&2; exit 64; }

bundle_manifest="${transfer_dir}/${bundle}.release-manifest.json"
bundle_images="${transfer_dir}/${bundle}.images.tar"
if [[ "${mode}" == export ]]; then
  [[ -n "${manifest}" && -f "${manifest}" ]] || { echo 'export requires --manifest <file>' >&2; exit 66; }
  mkdir -p "${transfer_dir}"
  [[ -w "${transfer_dir}" ]] || { echo "image transfer directory is not writable: ${transfer_dir}" >&2; exit 73; }
else
  [[ -z "${manifest}" ]] || { echo '--manifest is only valid with --export' >&2; exit 64; }
  [[ -r "${bundle_manifest}" && -r "${bundle_images}" ]] || { echo 'image transfer bundle is incomplete' >&2; exit 66; }
fi

active_manifest="${manifest:-${bundle_manifest}}"
candidate_text="$(python3 - "${active_manifest}" <<'PY'
import json,pathlib,re,sys

document=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
commit=document.get('scm',{}).get('commit')
if not isinstance(commit,str) or not re.fullmatch(r'[0-9a-f]{40}',commit):
    raise SystemExit('release manifest has an invalid scm commit')
components=document.get('components')
if not isinstance(components,list) or not components:
    raise SystemExit('release manifest has no components')
seen=set()
for item in components:
    if not isinstance(item,dict):
        raise SystemExit('release manifest has an invalid component')
    name=item.get('name')
    image_ref=item.get('imageRef')
    content_id=item.get('contentId')
    if name not in {'ai','back','front','game'} or name in seen:
        raise SystemExit('release manifest has invalid or duplicate component metadata')
    if item.get('storageMode') != 'local-docker' or item.get('sourceCommit') != commit:
        raise SystemExit(f'release manifest has invalid local candidate metadata: {name}')
    if not isinstance(image_ref,str) or not image_ref or not isinstance(content_id,str) or not re.fullmatch(r'sha256:[0-9a-f]{64}',content_id):
        raise SystemExit(f'release manifest has invalid candidate identity: {name}')
    seen.add(name)
    print(f'{image_ref}\t{content_id}')
PY
)" || exit $?
mapfile -t candidates <<<"${candidate_text}"

(( ${#candidates[@]} > 0 )) || { echo 'release manifest has no transferable candidates' >&2; exit 65; }

verify_images() {
  local candidate image_ref content_id actual
  for candidate in "${candidates[@]}"; do
    IFS=$'\t' read -r image_ref content_id <<<"${candidate}"
    actual="$(docker image inspect --format '{{.Id}}' "${image_ref}")"
    [[ "${actual}" == "${content_id}" ]] || { echo "candidate image content ID mismatch: ${image_ref}" >&2; exit 65; }
  done
}

if [[ "${mode}" == export ]]; then
  verify_images
  image_refs=()
  for candidate in "${candidates[@]}"; do image_refs+=("${candidate%%$'\t'*}"); done
  docker image save --output "${bundle_images}.tmp" "${image_refs[@]}"
  cp "${manifest}" "${bundle_manifest}.tmp"
  mv -f "${bundle_manifest}.tmp" "${bundle_manifest}"
  mv -f "${bundle_images}.tmp" "${bundle_images}"
else
  docker image load --input "${bundle_images}"
  verify_images
  rm -f -- "${bundle_images}" "${bundle_manifest}"
fi

printf '{"bundle":"%s","mode":"%s","images":%s}\n' "${bundle}" "${mode}" "${#candidates[@]}"
