"""GitLab 이슈 앞 번호대와 GitHub 이슈를 번호로 나란히 보여준다 (중복 판정 확인용)."""
import glob, json, os, sys
gh_api, gl_api = sys.argv[1], sys.argv[2]
gh = {}
for f in glob.glob(os.path.join(gh_api, "repo", "issues-and-pulls", "page-*.json")):
    for i in json.load(open(f, encoding="utf-8")):
        gh[i["number"]] = i
gl = {}
for f in glob.glob(os.path.join(gl_api, "project", "issues", "page-*.json")):
    for i in json.load(open(f, encoding="utf-8")):
        gl[i["iid"]] = i
for n in range(1, int(sys.argv[3]) + 1):
    a, b = gh.get(n), gl.get(n)
    print(n, "GH:", ("PR " if a and "pull_request" in a else "") + (a["title"][:40] if a else "-"), a and a["created_at"][:16], a and a["user"]["login"],
          "| GL:", b["title"][:40] if b else "-", b and b["created_at"][:16], b and b["author"]["username"], b and (b.get("description") or "")[:80].replace("\n", " "))

