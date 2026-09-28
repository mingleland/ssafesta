"""LFS 추적 확장자 파일의 과거 blob 내용 sha256 을 계산해, 서버 LFS 저장소에 같은 OID 가 있는지 batch API 로 조사한다.

history 에 pointer 로 남지 않았지만 서버에 올라간 LFS 객체(거절된 push·강제 push 등)를 찾기 위한 보조 조사.
"""
import base64
import hashlib
import json
import re
import subprocess
import sys
import urllib.request

repo, repo_url = sys.argv[1], sys.argv[2]
EXT = re.compile(r"\.(fbx|tga|psd|mp3|exr|skp)$", re.I)
objs = subprocess.run(["git", "-C", repo, "rev-list", "--objects", "--all"], capture_output=True).stdout.decode("utf-8", "replace").splitlines()
cand = {}
for l in objs:
    sha, _, path = l.partition(" ")
    if EXT.search(path):
        cand.setdefault(sha, path)
data = subprocess.run(["git", "-C", repo, "cat-file", "--batch"], input="\n".join(cand).encode(), capture_output=True).stdout
oids, i = {}, 0
while i < len(data):
    nl = data.index(b"\n", i)
    sha, typ, size = data[i:nl].split()
    body = data[nl + 1: nl + 1 + int(size)]
    i = nl + 1 + int(size) + 1
    if typ == b"blob":
        oids[hashlib.sha256(body).hexdigest()] = {"size": len(body), "blob": sha.decode(), "path": cand[sha.decode()]}
out = subprocess.run(["glab", "auth", "status", "-t", "--hostname", "lab.ssafy.com"], capture_output=True, text=True, encoding="utf-8", errors="replace")
tok = re.search(r"Token found[^:]*:\s*(\S+)", out.stdout + out.stderr).group(1)
auth = "Basic " + base64.b64encode(f"colosair:{tok}".encode()).decode()
found = []
keys = list(oids)
for j in range(0, len(keys), 100):
    batch = [{"oid": k, "size": oids[k]["size"]} for k in keys[j:j + 100]]
    req = urllib.request.Request(repo_url + "/info/lfs/objects/batch", method="POST",
                                 data=json.dumps({"operation": "download", "transfers": ["basic"], "objects": batch}).encode(),
                                 headers={"Authorization": auth, "Accept": "application/vnd.git-lfs+json", "Content-Type": "application/vnd.git-lfs+json"})
    for o in json.load(urllib.request.urlopen(req))["objects"]:
        if "error" not in o:
            found.append({"oid": o["oid"], **oids[o["oid"]]})
print(json.dumps({"candidates": len(oids), "on_server": found}, ensure_ascii=False, indent=1))

