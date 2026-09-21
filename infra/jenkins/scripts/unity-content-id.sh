#!/usr/bin/env bash
# festa-unity 트리를 **파일 내용 기준**으로 식별한다 (S15P21A604-939).
#
# 왜 git tree hash 를 쓰지 않는가: 같은 파일이 raw blob 으로도, Git LFS pointer 로도 커밋될 수 있고
# 그때 blob sha 가 달라진다. 2026-09-21 에 FBX 두 개가 pointer 로 정규화되면서 develop 의 tree hash 가
# 바뀌었고, 내용이 한 바이트도 다르지 않은 Unity Release Bundle(509e0535)이 "다른 Unity 입력" 으로
# 거부됐다. Unity 가 실제로 읽는 것은 표현이 아니라 내용이므로 내용으로 판정한다.
#
# 판정 방식은 파일마다 하나다.
#   LFS pointer  -> content:<pointer 의 oid sha256>:<pointer 가 적은 size>
#   그 밖의 blob -> content:<blob 내용의 sha256>:<blob 길이>
# LFS 의 oid 가 곧 원본 내용의 sha256 이라 두 표현이 같은 값으로 모인다.
# 여기에 경로와 mode 를 붙여 경로순으로 해시한다.
#
# .gitattributes 를 해석하지 않는다. 추적 규칙이 바뀌거나 누가 수동으로 pointer 를 커밋해도
# 내용만 보면 답이 같기 때문이다.
#
# 사용: unity-content-id.sh <commit> [--repo DIR]
# 출력: 64자 sha256 (실패하면 아무것도 출력하지 않고 exit != 0 — 호출자가 fallback 을 고른다)
set -euo pipefail

usage() { echo 'Usage: unity-content-id.sh <commit> [--repo DIR]' >&2; exit 64; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
commit=''
repo="${GIT_REPO_DIR:-$(cd "${script_dir}/../../.." && pwd)}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) repo="${2:-}"; shift 2 ;;
    -*) usage ;;
    *) [[ -z "${commit}" ]] || usage; commit="$1"; shift ;;
  esac
done
[[ -n "${commit}" && -d "${repo}" ]] || usage

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

UNITY_TREE_PREFIX="${UNITY_TREE_PREFIX:-festa-unity}" "${python_bin}" - "${repo}" "${commit}" <<'PY'
import hashlib
import os
import subprocess
import sys
import tempfile

repo, commit = sys.argv[1:3]
prefix = os.environ['UNITY_TREE_PREFIX']
POINTER_MAGIC = b'version https://git-lfs.github.com/spec/v1'

try:
    listing = subprocess.run(['git', '-C', repo, 'ls-tree', '-r', commit, '--', prefix],
                             capture_output=True, check=True).stdout
except subprocess.CalledProcessError:
    sys.exit(65)

entries = []
for line in listing.decode('utf-8', 'surrogateescape').splitlines():
    meta, path = line.split('\t', 1)
    mode, kind, blob = meta.split()
    if kind == 'blob':
        entries.append((path, mode, blob))
entries.sort()
if not entries:
    sys.exit(65)


def pointer_identity(body):
    """LFS pointer 면 oid·size 를, 아니면 None 을 준다."""
    if not body.startswith(POINTER_MAGIC):
        return None
    oid = size = None
    for line in body.decode('utf-8', 'replace').splitlines():
        if line.startswith('oid sha256:'):
            oid = line.split('sha256:', 1)[1].strip()
        elif line.startswith('size '):
            size = line.split(' ', 1)[1].strip()
    return 'content:' + oid + ':' + size if oid and size else None


digest = hashlib.sha256()
# 요청 목록은 임시 파일로 넘긴다. stdin 파이프에 전부 쓰면서 동시에 stdout 을 읽지 않으면
# 파이프 버퍼(64KB)가 차는 순간 양쪽이 서로를 기다리며 멈춘다 — 4,816개면 반드시 걸린다.
with tempfile.TemporaryFile('w+b') as request:
    request.write(''.join(blob + '\n' for _, _, blob in entries).encode())
    request.seek(0)
    batch = subprocess.Popen(['git', '-C', repo, 'cat-file', '--batch'],
                             stdin=request, stdout=subprocess.PIPE)
    try:
        for path, mode, blob in entries:
            header = batch.stdout.readline().split()
            if len(header) < 3 or header[1] != b'blob':
                sys.exit(65)
            body = batch.stdout.read(int(header[2]))
            batch.stdout.read(1)  # 객체 뒤의 개행
            identity = pointer_identity(body)
            if identity is None:
                identity = 'content:' + hashlib.sha256(body).hexdigest() + ':' + str(len(body))
            digest.update((path + '\x00' + mode + '\x00' + identity + '\n').encode('utf-8', 'surrogateescape'))
    finally:
        batch.stdout.close()
        if batch.wait() != 0:
            sys.exit(65)

print(digest.hexdigest())
PY
