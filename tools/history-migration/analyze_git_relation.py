"""최초 GitHub 저장소와 GitLab 저장소의 커밋·브랜치·태그 관계를 분석한다 (Phase B)."""
import json
import subprocess
import sys

gh_repo, gl_repo, out = sys.argv[1], sys.argv[2], sys.argv[3]


def git(repo, *a, inp=None):
    return subprocess.run(["git", "-C", repo, *a], input=inp, capture_output=True, text=True, encoding="utf-8").stdout


def refs(repo):
    return dict(l.split(" ", 1)[::-1] for l in git(repo, "for-each-ref", "--format=%(objectname) %(refname)").splitlines())


gh_refs, gl_refs = refs(gh_repo), refs(gl_repo)
gh_c = set(git(gh_repo, "rev-list", "--all").split())
gl_c = set(git(gl_repo, "rev-list", "--all").split())
gh_only = sorted(gh_c - gl_c)
res = {"github_commits": len(gh_c), "gitlab_commits": len(gl_c), "shared": len(gh_c & gl_c), "github_only": len(gh_only)}
# 각 GitHub ref 가 GitLab 에 그대로 있는지
rows = []
for name, sha in sorted(gh_refs.items()):
    tip = git(gh_repo, "rev-parse", f"{sha}^{{commit}}").strip() or sha
    same = [n for n, s in gl_refs.items() if s == sha]
    rows.append({"ref": name, "sha": sha, "tip_in_gitlab": tip in gl_c, "same_ref_sha_in_gitlab": same[:5]})
res["github_refs"] = rows
# GitHub 전용 커밋이 어느 GitHub ref 에서 닿는지
only_by_ref = {}
if gh_only:
    for name, sha in gh_refs.items():
        reach = set(git(gh_repo, "rev-list", sha, "--not", *[s for s in gl_c if False]).split()) & set(gh_only)
        if reach:
            only_by_ref[name] = len(reach)
res["github_only_reachable_from"] = only_by_ref
res["github_only_commits"] = [dict(zip(("sha", "author", "date", "subject"), git(gh_repo, "log", "-1", "--format=%H%x1f%an <%ae>%x1f%aI%x1f%s", s).strip().split("\x1f"))) for s in gh_only]
json.dump(res, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print({k: v for k, v in res.items() if not isinstance(v, (list, dict))}, "only_by_ref:", only_by_ref)
print("github refs whose tip is missing in gitlab:", [r["ref"] for r in rows if not r["tip_in_gitlab"]])

