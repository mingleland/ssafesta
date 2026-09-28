"""원본 백업 전체의 SHA-256 목록을 만든다 (sha256sum 호환 형식). git 저장소 디렉터리는 bundle 로 대신 검증하므로 제외한다."""
import hashlib
import os
import sys

root, out = os.path.abspath(sys.argv[1]), sys.argv[2]
skip_dirs = {d for d in sys.argv[3:]}
n = total = 0
with open(out, "w", encoding="utf-8", newline="\n") as f:
    for d, dirs, files in os.walk(root):
        dirs[:] = sorted(x for x in dirs if not x.endswith(".git") and os.path.relpath(os.path.join(d, x), root) not in skip_dirs)
        for fn in sorted(files):
            p = os.path.join(d, fn)
            h = hashlib.sha256()
            with open(p, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            rel = os.path.relpath(p, root).replace("\\", "/")
            f.write(f"{h.hexdigest()}  {rel}\n")
            n += 1
            total += os.path.getsize(p)
print(f"files {n} bytes {total}")

