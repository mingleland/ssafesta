"""GEI 이전 결과 번호로 project-history/mappings/items.json 을 다시 쓴다 (MR iid = PR 번호, 이슈 = iid + 1410)."""
import json
import os
import sys

root, hist = sys.argv[1], sys.argv[2]
bm = json.load(open(os.path.join(root, "gei", "archive-build-map.json"), encoding="utf-8"))
OFF = 1410
FAILED = {1167: "diff 없음", 1212: "커밋 없음(head/base 없음)"}
items = []
for line in open(os.path.join(root, "gei", "archive", "tree", "project", "merge_requests.ndjson"), encoding="utf-8"):
    m = json.loads(line)
    iid = m["iid"]
    orig = next((k for k, v in bm["github_original"].items() if v["iid"] == iid and v["type"] == "merge_request"), f"GitLab !{iid}")
    items.append({"original": orig, "github": None if iid in FAILED else f"PR #{iid}", "note": FAILED.get(iid), "title": m["title"], "created_at": m["created_at"]})
for line in open(os.path.join(root, "gei", "archive", "tree", "project", "issues.ndjson"), encoding="utf-8"):
    i = json.loads(line)
    orig = next((k for k, v in bm["github_original"].items() if v["iid"] == i["iid"] and v["type"] == "issue"), f"GitLab #{i['iid']}")
    items.append({"original": orig, "github": f"Issue #{i['iid'] + OFF}", "title": i["title"], "created_at": i["created_at"]})
doc = {"repo": "mingleland/ssafesta", "method": "GitHub Enterprise Importer (gh gl2gh), 원본 덤프로 구성한 GitLab export archive",
       "rules": {"GitLab !N": "PR #N", "GitLab #N (최초 GitHub 사본 제외)": "Issue #(N+1410)", "최초 GitHub 이슈 #n": "Issue #(n+1410)",
                 "최초 GitHub PR": "PR #1364~#1410 (원래 번호 순)"},
       "gitlab_issue_copies_of_github": "GitLab 이슈 #1~#83 은 최초 GitHub #1~#83 의 이관 사본이라 GitHub 원본 기록으로 대체했다",
       "items": items}
json.dump(doc, open(os.path.join(hist, "mappings", "items.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(items))

