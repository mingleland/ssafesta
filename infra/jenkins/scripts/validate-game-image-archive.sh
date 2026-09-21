#!/usr/bin/env bash
# docker save 아카이브를 load 하지 않고 identity(태그·config digest·label)를 검사한다 — 외부 fixture 는 완전 read-only 여야 한다 (Batch 2).
set -euo pipefail
usage() { echo 'Usage: validate-game-image-archive.sh TAR --image-ref REF --content-id sha256:HEX [--source-commit SHA]' >&2; exit 64; }
[[ $# -ge 1 ]] || usage
archive="$1"; shift
image_ref='' content_id='' expected_commit=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --image-ref) image_ref="${2:-}"; shift 2 ;;
    --content-id) content_id="${2:-}"; shift 2 ;;
    --source-commit) expected_commit="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ -f "${archive}" ]] || { echo "image archive not found: ${archive}" >&2; exit 66; }
[[ -n "${image_ref}" && "${content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || usage
[[ -z "${expected_commit}" || "${expected_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
"${PYTHON_BIN:-python3}" - "${archive}" "${image_ref}" "${content_id}" "${expected_commit}" <<'PY'
import hashlib, json, sys, tarfile
archive_path, image_ref, content_id, expected_commit = sys.argv[1:5]
def die(msg): raise SystemExit(f'GAME_ARCHIVE_INVALID: {msg}')
with tarfile.open(archive_path, 'r:*') as archive:
    try: manifest = json.load(archive.extractfile(archive.getmember('manifest.json')))
    except KeyError: die('archive has no manifest.json')
    matches = [item for item in manifest if image_ref in (item.get('RepoTags') or [])]
    if len(matches) != 1: die(f'archive does not contain exactly one image tagged {image_ref}')
    config_name = matches[0].get('Config')
    try: config_bytes = archive.extractfile(archive.getmember(config_name)).read()
    except (KeyError, TypeError): die('archive image config is missing')
    for layer in matches[0].get('Layers') or []:
        try: archive.getmember(layer)
        except KeyError: die(f'archive layer is missing: {layer}')
# docker 의 .Id 는 image store 에 따라 다른 것을 가리킨다: legacy store 는 config digest, containerd store 는
# OCI index 의 manifest digest(index.json). 두 도메인 중 하나가 정확히 contentId 와 같아야 하고, 그 blob 은
# 이름 그대로의 해시를 가져야 한다.
config_digest = 'sha256:' + hashlib.sha256(config_bytes).hexdigest()
with tarfile.open(archive_path, 'r:*') as archive:
    index_digests = set()
    try:
        index = json.load(archive.extractfile(archive.getmember('index.json')))
        index_digests = {m.get('digest') for m in index.get('manifests') or []}
    except KeyError:
        pass
    if content_id != config_digest and content_id not in index_digests:
        die(f'contentId {content_id} matches neither config digest {config_digest} nor an OCI index manifest digest')
    if content_id in index_digests:
        try: blob = archive.extractfile(archive.getmember('blobs/' + content_id.replace(':', '/'))).read()
        except (KeyError, TypeError): die(f'archive blob for contentId {content_id} is missing')
        if 'sha256:' + hashlib.sha256(blob).hexdigest() != content_id: die('archive blob for contentId is corrupt')
labels = ((json.loads(config_bytes).get('config') or {}).get('Labels') or {})
commit = labels.get('org.ssafy-festa.source-commit')
for key, value in {'org.ssafy-festa.component': 'game', 'org.ssafy-festa.managed': 'true'}.items():
    if labels.get(key) != value: die(f'label {key}: expected {value!r} got {labels.get(key)!r}')
if not isinstance(commit, str) or len(commit) != 40: die('label org.ssafy-festa.source-commit is not a full SHA')
if expected_commit and commit != expected_commit: die(f'label source-commit {commit} != expected {expected_commit}')
print(f'GAME_ARCHIVE_OK {commit} {content_id}')
PY
