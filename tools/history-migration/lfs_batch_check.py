"""LFS batch API 로 OID 가 서버에 있는지 확인하고, 있으면 받아 크기·sha256 을 검증해 저장한다."""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
import base64

repo_url, out_dir, *pairs = sys.argv[1:]
out = subprocess.run(["glab", "auth", "status", "-t", "--hostname", "lab.ssafy.com"], capture_output=True, text=True, encoding="utf-8", errors="replace")
tok = re.search(r"Token found[^:]*:\s*(\S+)", out.stdout + out.stderr).group(1)
auth = "Basic " + base64.b64encode(f"colosair:{tok}".encode()).decode()
objs = [{"oid": p.split(":")[0], "size": int(p.split(":")[1])} for p in pairs]
req = urllib.request.Request(repo_url + "/info/lfs/objects/batch", method="POST",
                             data=json.dumps({"operation": "download", "transfers": ["basic"], "objects": objs}).encode(),
                             headers={"Authorization": auth, "Accept": "application/vnd.git-lfs+json", "Content-Type": "application/vnd.git-lfs+json"})
resp = json.load(urllib.request.urlopen(req))
for o in resp["objects"]:
    if "error" in o:
        print(o["oid"], "ERROR", o["error"])
        continue
    act = o["actions"]["download"]
    data = urllib.request.urlopen(urllib.request.Request(act["href"], headers=act.get("header", {}))).read()
    ok = hashlib.sha256(data).hexdigest() == o["oid"] and len(data) == o["size"]
    path = os.path.join(out_dir, o["oid"][:2], o["oid"][2:4], o["oid"])
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)
    print(o["oid"], "downloaded", len(data), "verified", ok)

