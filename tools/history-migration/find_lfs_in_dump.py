"""API 덤프 본문(diff 등)에 등장하는 LFS pointer OID 를 찾는다 — 미러에 없는 LFS 객체 후보를 찾기 위한 보조 도구."""
import collections
import os
import re
import sys

found = collections.defaultdict(set)
for root in sys.argv[1:]:
    for d, _, fs in os.walk(root):
        for f in fs:
            b = open(os.path.join(d, f), "rb").read()
            for m in re.finditer(rb"sha256:([0-9a-f]{64})(.{0,40})", b):
                size = re.search(rb"size (\d+)", m.group(2))
                found[(m.group(1).decode(), int(size.group(1)) if size else None)].add(os.path.relpath(os.path.join(d, f), root))
for k, v in sorted(found.items()):
    print(k, len(v), sorted(v)[:4])

