"""GitLab 바이너리·보조 원본 수집: 업로드 실물, 커밋 댓글, job 로그, generic package 파일을 받아 크기·해시를 검증한다.

사용법: python gitlab_extras.py <api 덤프 디렉터리> <git 저장소> <phase ...>
  phase: uploads commit_notes job_traces packages
gitlab_dump.py 의 http/save 를 재사용하고, 이미 받은 파일(크기 일치)은 건너뛴다.
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

OUT, REPO, PHASES = os.path.abspath(sys.argv[1]), sys.argv[2], sys.argv[3:]
sys.argv = [sys.argv[0], OUT, *[f"x-{p}" for p in PHASES]]  # gitlab_dump 가 manifest 이름에 phase 를 쓴다
sys.path.insert(0, os.path.dirname(__file__))
import gitlab_dump as g  # noqa: E402

API = os.path.join(OUT, "api")
P = g.P


def pages(pattern):
    for f in sorted(glob.glob(os.path.join(API, pattern))):
        yield from json.load(open(f, encoding="utf-8"))


def safe(name):
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")[:150] or "_"


def binary(rel, url, expected=None):
    path = os.path.join(OUT, rel)
    if expected is not None and os.path.exists(path) and os.path.getsize(path) == expected:
        data = open(path, "rb").read()
        return {"file": rel.replace("\\", "/"), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "status": "cached"}
    status, payload, headers = g.http(url)
    rec = {"url": g.BASE + url if url.startswith("/") else url, "status": status, "expected": expected}
    if status == 200:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(payload)
        rec.update(file=rel.replace("\\", "/"), bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest(),
                   size_match=(expected is None or expected == len(payload)))
    else:
        rec["error"] = payload[:300].decode("utf-8", "replace")
    return rec


def write(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def phase_uploads():
    ups = list(pages("project/uploads/page-*.json"))
    with ThreadPoolExecutor(4) as ex:
        by_id = list(ex.map(lambda u: {"upload": u, **binary(os.path.join("uploads", "by-id", str(u["id"]), safe(u["filename"])),
                                                               f"{P}/uploads/{u['id']}", u["size"])}, ups))
    # 본문에 링크된 업로드 (secret/filename) — 본문 링크와 실물을 잇기 위해 따로 받는다
    refs = set()
    for f in glob.glob(os.path.join(API, "**", "*.json"), recursive=True):
        text = open(f, "rb").read().decode("utf-8", "replace")
        for m in re.finditer(r"/uploads/([0-9a-f]{32})/([^\s\)\]\"'<>\\]+)", text):
            refs.add((m.group(1), m.group(2)))
    def get_ref(sf):
        secret, fn = sf
        name = urllib.parse.unquote(fn)
        rec = binary(os.path.join("uploads", "by-secret", secret, safe(name)), f"{P}/uploads/{secret}/{urllib.parse.quote(name, safe='')}")
        return {"secret": secret, "filename": name, **rec}
    with ThreadPoolExecutor(4) as ex:
        by_ref = list(ex.map(get_ref, sorted(refs)))
    hashes = {r.get("sha256"): r["upload"]["id"] for r in by_id if r.get("sha256")}
    for r in by_ref:
        r["upload_id"] = hashes.get(r.get("sha256"))
    write("uploads-manifest.json", {"by_id": by_id, "by_reference": by_ref})
    print(f"uploads by id {sum(1 for r in by_id if r.get('size_match') or r.get('status') == 'cached')}/{len(by_id)}; "
          f"referenced {sum(1 for r in by_ref if r.get('sha256'))}/{len(by_ref)}", flush=True)


def phase_commit_notes():
    shas = subprocess.run(["git", "-C", REPO, "rev-list", "--all"], capture_output=True, text=True).stdout.split()
    def one(s):
        items = g.paged(f"commits/{s}/discussions", f"{P}/repository/commits/{s}/discussions")
        return s, len(items)
    with ThreadPoolExecutor(8) as ex:
        res = list(ex.map(one, shas))
    with_notes = {s: n for s, n in res if n}
    write("commit-notes-summary.json", {"commits_checked": len(shas), "commits_with_discussions": with_notes})
    print(f"commits checked {len(shas)}, with discussions {len(with_notes)}", flush=True)


def phase_job_traces():
    jobs = {j["id"]: j for j in pages("pipelines/*/jobs/page-*.json")}
    jobs.update({j["id"]: j for j in pages("pipelines/*/bridges/page-*.json") if False})
    def one(jid):
        rel = os.path.join("job-traces", f"{jid}.log")
        if os.path.exists(os.path.join(OUT, rel)):
            return {"job": jid, "status": "cached"}
        rec = binary(rel, f"{P}/jobs/{jid}/trace")
        return {"job": jid, **{k: rec.get(k) for k in ("status", "bytes", "sha256")}}
    with ThreadPoolExecutor(8) as ex:
        res = list(ex.map(one, sorted(jobs)))
    write("job-traces-manifest.json", res)
    print(f"jobs {len(jobs)}, traces ok {sum(1 for r in res if r['status'] in (200, 'cached'))}", flush=True)


def phase_packages():
    res = []
    for d in sorted(glob.glob(os.path.join(API, "project", "packages", "*"))):
        if not os.path.isdir(d):
            continue
        pk = json.load(open(os.path.join(d, "package.json"), encoding="utf-8"))
        for pf in pages(os.path.relpath(os.path.join(d, "package_files", "page-*.json"), API)):
            url = f"{P}/packages/generic/{urllib.parse.quote(pk['name'])}/{urllib.parse.quote(pk['version'])}/{urllib.parse.quote(pf['file_name'])}"
            rec = binary(os.path.join("packages", f"{pk['id']}-{safe(pk['name'])}-{safe(pk['version'])}", safe(pf["file_name"])), url, pf["size"])
            rec["sha256_meta"] = pf.get("file_sha256")
            rec["sha256_match"] = pf.get("file_sha256") in (None, rec.get("sha256"))
            res.append({"package_id": pk["id"], "file_id": pf["id"], **rec})
            print(pk["id"], pf["file_name"], rec.get("status"), rec.get("bytes"), flush=True)
    write("packages-manifest.json", res)


for ph in PHASES:
    print("phase", ph, flush=True)
    globals()[f"phase_{ph}"]()

