"""LFS pointer 전수 조사: 미러의 모든 ref 에서 LFS pointer blob 을 찾아 OID·크기·경로·로컬 보유 여부를 기록한다."""
import json
import os
import subprocess
import sys

repo = sys.argv[1]


def git(*args, inp=None):
    return subprocess.run(["git", "-C", repo, *args], input=inp, capture_output=True, check=True).stdout


objs = git("rev-list", "--objects", "--all", *sys.argv[2:]).decode("utf-8", "replace").splitlines()
paths = {}
for line in objs:
    sha, _, path = line.partition(" ")
    paths.setdefault(sha, set()).add(path)
check = git("cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)", inp="\n".join(paths).encode()).decode().splitlines()
small = [l.split()[0] for l in check if l.split()[1] == "blob" and int(l.split()[2]) < 1024]
data = git("cat-file", "--batch", inp="\n".join(small).encode())
pointers, i = {}, 0
while i < len(data):
    nl = data.index(b"\n", i)
    sha, _, size = data[i:nl].split()
    body = data[nl + 1: nl + 1 + int(size)]
    i = nl + 1 + int(size) + 1
    if body.startswith(b"version https://git-lfs"):
        kv = dict(l.split(" ", 1) for l in body.decode().splitlines() if " " in l)
        oid = kv["oid"].split(":", 1)[1]
        p = pointers.setdefault(oid, {"oid": oid, "size": int(kv["size"]), "pointer_blobs": [], "paths": set()})
        p["pointer_blobs"].append(sha.decode())
        p["paths"] |= paths[sha.decode()]
for p in pointers.values():
    local = os.path.join(repo, "lfs", "objects", p["oid"][:2], p["oid"][2:4], p["oid"])
    p["local"] = os.path.exists(local) and os.path.getsize(local) == p["size"]
    p["paths"] = sorted(p["paths"])
print(json.dumps(sorted(pointers.values(), key=lambda x: x["oid"]), ensure_ascii=False, indent=1))

