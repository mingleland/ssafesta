"""새 저장소에 올릴 ref 를 스테이징 저장소에 모은다: GitLab 브랜치·태그 + 최초 GitHub 브랜치 + 삭제 브랜치 복구분 + PR 재구성용 임시 브랜치.

사용법: python stage_refs.py <migration 루트>   → work/staging.git 생성, work/refs-plan.json 기록
"""
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(sys.argv[1])
ST = os.path.join(ROOT, "work", "staging.git")
COMPLETE = os.path.join(ROOT, "raw", "gitlab", "S15P21A604-complete.git")
GHM = os.path.join(ROOT, "raw", "github-original", "ssafesta.git")


def git(*a, repo=ST, inp=None):
    r = subprocess.run(["git", "-C", repo, *a], input=inp.encode() if inp else None, capture_output=True)
    if r.returncode:
        raise SystemExit(f"git {a[:3]} failed: {r.stderr[-500:].decode('utf-8', 'replace')}")
    return r.stdout.decode("utf-8")


if not os.path.exists(ST):
    subprocess.run(["git", "clone", "-q", "--mirror", COMPLETE, ST], check=True)
refs = dict(l.split(" ", 1)[::-1] for l in git("for-each-ref", "--format=%(objectname) %(refname)").splitlines())
# GitLab 전용 ref(merge-requests·archive) 는 PR/보관 브랜치로 대신하므로 지운다 (스테이징 사본에서만)
upd = [f"delete {r}" for r in refs if not (r.startswith("refs/heads/") or r.startswith("refs/tags/"))]
if upd:
    git("update-ref", "--stdin", inp="\n".join(upd) + "\n")
git("fetch", "-q", "--no-tags", GHM, "+refs/heads/*:refs/heads/github-original/*")
added = {"github-original": git("for-each-ref", "--format=%(refname)", "refs/heads/github-original").split()}
# 삭제된 브랜치 끝 커밋 (push 이벤트로 확인, 서버 또는 로컬 clone 에서 복구)
ev = json.load(open(os.path.join(ROOT, "logs", "event-missing-commits.json"), encoding="utf-8"))["missing"]
arch = []
for sha, src in ev.items():
    branch = src.split()[2]
    ok = subprocess.run(["git", "-C", ST, "cat-file", "-e", sha + "^{commit}"]).returncode == 0
    if not ok:
        r = subprocess.run(["git", "-C", ST, "fetch", "-q", "--no-tags", COMPLETE, sha], capture_output=True)
        ok = r.returncode == 0
    if ok:
        name = f"refs/heads/archive/deleted-branches/{branch}@{sha[:8]}"
        git("update-ref", name, sha)
        arch.append(name)
added["archive"] = arch
# PR 재구성용 임시 브랜치
specs = open(os.path.join(ROOT, "work", "pr-refspecs.txt"), encoding="utf-8").read().split()
missing = []
lines = []
for s in specs:
    sha, ref = s.split(":", 1)
    if subprocess.run(["git", "-C", ST, "cat-file", "-e", sha + "^{commit}"]).returncode != 0:
        subprocess.run(["git", "-C", ST, "fetch", "-q", "--no-tags", COMPLETE, sha], capture_output=True)
        if subprocess.run(["git", "-C", ST, "cat-file", "-e", sha + "^{commit}"]).returncode != 0:
            subprocess.run(["git", "-C", ST, "fetch", "-q", "--no-tags", GHM, sha], capture_output=True)
    if subprocess.run(["git", "-C", ST, "cat-file", "-e", sha + "^{commit}"]).returncode != 0:
        missing.append(s)
        continue
    lines.append(f"update {ref} {sha}")
git("update-ref", "--stdin", inp="\n".join(lines) + "\n")
final = git("for-each-ref", "--format=%(refname)").split()
summary = {"total_refs": len(final), "heads": sum(1 for r in final if r.startswith("refs/heads/") and not r.startswith(("refs/heads/gl-mr/", "refs/heads/gh-pr/", "refs/heads/github-original/", "refs/heads/archive/"))),
           "tags": sum(1 for r in final if r.startswith("refs/tags/")), "github_original": len(added["github-original"]), "archive_deleted": len(arch),
           "pr_temp": len(lines), "pr_temp_missing": missing}
json.dump(summary, open(os.path.join(ROOT, "work", "refs-plan.json"), "w", encoding="utf-8"), indent=1)
print(json.dumps(summary, indent=1))

