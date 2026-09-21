#!/usr/bin/env bash
# WebGL release zip 을 열어 보지 않고(설치 없이) 계약을 검사한다 — 산출물 하나로 publisher·fixture·deploy 가 같은 판정을 쓴다 (Batch 2).
set -euo pipefail
usage() { echo 'Usage: validate-webgl-archive.sh ZIP [--manifest EXTERNAL_MANIFEST] [--source-commit SHA] [--branch develop]' >&2; exit 64; }
[[ $# -ge 1 ]] || usage
archive="$1"; shift
external_manifest='' expected_commit='' expected_branch="${WEBGL_EXPECTED_BRANCH:-develop}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) external_manifest="${2:-}"; shift 2 ;;
    --source-commit) expected_commit="${2:-}"; shift 2 ;;
    --branch) expected_branch="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ -f "${archive}" ]] || { echo "WebGL zip not found: ${archive}" >&2; exit 66; }
[[ -z "${expected_commit}" || "${expected_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
# 빈 문자열로 주면 unityVersion 검사를 건너뛴다(테스트 픽스처용). 미설정이면 프로젝트 고정 버전.
"${PYTHON_BIN:-python3}" - "${archive}" "${external_manifest}" "${expected_commit}" "${expected_branch}" "${WEBGL_EXPECTED_UNITY_VERSION-6000.0.78f1}" <<'PY'
import hashlib, json, pathlib, re, sys, zipfile
archive_path, external, expected_commit, expected_branch, unity_version = sys.argv[1:6]
def die(msg): raise SystemExit(f'WEBGL_ARCHIVE_INVALID: {msg}')
with zipfile.ZipFile(archive_path) as archive:
    bad = archive.testzip()
    if bad: die(f'corrupt zip entry: {bad}')
    names = set(archive.namelist())
    for required in ('index.html', 'manifest.json'):
        if required not in names: die(f'zip is missing {required}')
    for required in ('Build/', 'TemplateData/'):
        if not any(n.startswith(required) for n in names): die(f'zip is missing {required}')
    if any(n.startswith('/') or '..' in n.split('/') for n in names): die('zip contains unsafe paths')
    manifest = json.loads(archive.read('manifest.json').decode('utf-8'))
for key in ('loaderUrl', 'dataUrl', 'frameworkUrl', 'codeUrl'):
    value = manifest.get(key)
    if not isinstance(value, str) or value not in names: die(f'manifest {key} does not point at a zip entry: {value!r}')
commit = manifest.get('sourceCommit')
if not isinstance(commit, str) or not re.fullmatch(r'[0-9a-f]{40}', commit): die('manifest sourceCommit is not a full SHA')
if expected_commit and commit != expected_commit: die(f'manifest sourceCommit {commit} != expected {expected_commit}')
if manifest.get('sourceBranch') != expected_branch: die(f'manifest sourceBranch {manifest.get("sourceBranch")!r} != {expected_branch!r}')
if manifest.get('dirty') is not False: die(f'manifest dirty must be false (got {manifest.get("dirty")!r})')
if manifest.get('buildProfile') != 'release': die(f'manifest buildProfile {manifest.get("buildProfile")!r} != release')
if unity_version and manifest.get('unityVersion') != unity_version: die(f'manifest unityVersion {manifest.get("unityVersion")!r} != {unity_version}')
if external:
    outside = json.loads(pathlib.Path(external).read_text(encoding='utf-8'))
    if outside != manifest: die('external manifest differs from the manifest inside the zip')
sha = hashlib.sha256(pathlib.Path(archive_path).read_bytes()).hexdigest()
print(f'WEBGL_ARCHIVE_OK {commit} {sha}')
PY
