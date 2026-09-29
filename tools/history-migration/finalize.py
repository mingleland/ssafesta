"""이전 마무리: 실제 병합 커밋 push 로 PR 을 Merged 로 만들고, 임시 브랜치를 정리하고, 결과를 원본 계획과 대조한다.

사용법: python finalize.py <migration 루트> <owner/repo> merge|cleanup|verify
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT, REPO, STEP = os.path.abspath(sys.argv[1]), sys.argv[2], sys.argv[3]
ST = os.path.join(ROOT, "work", "staging.git")
PLAN = json.load(open(os.path.join(ROOT, "work", "plan.json"), encoding="utf-8"))
STATE = json.load(open(os.path.join(ROOT, "work", "state.json"), encoding="utf-8"))["items"]
TOKEN = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
ENV = dict(os.environ, GCM_INTERACTIVE="never", GIT_TERMINAL_PROMPT="0")


def gql(q, v):
    req = urllib.request.Request("https://api.github.com/graphql", method="POST", data=json.dumps({"query": q, "variables": v}).encode(),
                                 headers={"Authorization": f"Bearer {TOKEN}"})
    return json.load(urllib.request.urlopen(req))


def push(specs):
    for i in range(0, len(specs), 50):
        r = subprocess.run(["git", "-C", ST, "push", "--no-verify", "github", *specs[i:i + 50]], capture_output=True, text=True, env=ENV)
        print(i + len(specs[i:i + 50]), "rc", r.returncode, r.stderr.strip().splitlines()[-1:] if r.returncode else "", flush=True)
        if r.returncode:
            raise SystemExit(r.stderr[-2000:])


def done(p):
    s = STATE.get(str(p["number"]), {})
    return s.get("complete") and s.get("kind") == "pr"


if STEP == "merge":
    specs = [f"{p['merge_push_sha']}:refs/heads/{p['base_ref']}" for p in PLAN if p.get("merge_push_sha") and done(p) and p["final_state"] == "merged"]
    print("merge pushes", len(specs))
    push(specs)
elif STEP == "cleanup":
    keep = {p["head_ref"] for p in PLAN if p.get("head_ref") and p["final_state"] == "open"} | {p["base_ref"] for p in PLAN if p.get("base_ref") and p["final_state"] == "open"}
    refs = [p[k] for p in PLAN if p.get("head_ref") and STATE.get(str(p["number"]), {}).get("complete") for k in ("head_ref", "base_ref")]
    specs = [f":refs/heads/{r}" for r in refs if r not in keep]
    print("delete temp branches", len(specs), "keep", len(keep))
    push(specs)
elif STEP == "verify":
    Q = """query($o:String!,$n:String!,$c:String){repository(owner:$o,name:$n){
      issues(first:100,after:$c){pageInfo{hasNextPage endCursor} nodes{number title state stateReason comments{totalCount} labels(first:30){nodes{name}}}}}}"""
    QP = """query($o:String!,$n:String!,$c:String){repository(owner:$o,name:$n){
      pullRequests(first:100,after:$c){pageInfo{hasNextPage endCursor} nodes{number title state merged mergeCommit{oid} comments{totalCount} commits{totalCount} baseRefOid headRefOid labels(first:30){nodes{name}}}}}}"""
    o, n = REPO.split("/")
    got = {}
    for q, key in ((Q, "issues"), (QP, "pullRequests")):
        c = None
        while True:
            d = gql(q, {"o": o, "n": n, "c": c})["data"]["repository"][key]
            for x in d["nodes"]:
                got[x["number"]] = {**x, "kind": "pr" if key == "pullRequests" else "issue"}
            if not d["pageInfo"]["hasNextPage"]:
                break
            c = d["pageInfo"]["endCursor"]
    problems = []
    for p in PLAN:
        s = STATE.get(str(p["number"]), {})
        g = got.get(p["number"])
        if not g:
            problems.append((p["number"], "missing"))
            continue
        exp_kind = s.get("kind", p["kind"])
        want = p["final_state"] if exp_kind == p["kind"] else "closed"
        state = "merged" if g.get("merged") else g["state"].lower()
        if g["kind"] != exp_kind:
            problems.append((p["number"], f"kind {g['kind']} != {exp_kind}"))
        if g["title"] != p["title"]:
            problems.append((p["number"], "title"))
        if state != want:
            problems.append((p["number"], f"state {state} != {want}"))
        if g["comments"]["totalCount"] != len(p["comments"]):
            problems.append((p["number"], f"comments {g['comments']['totalCount']} != {len(p['comments'])}"))
        if set(l["name"] for l in g["labels"]["nodes"]) != set(p["labels"]):
            problems.append((p["number"], "labels"))
        if g["kind"] == "pr" and (g["headRefOid"] != p["head_sha"]):
            problems.append((p["number"], "head sha"))
        if state == "merged" and g["mergeCommit"]["oid"] != p.get("merge_push_sha"):
            problems.append((p["number"], "merge commit"))
    extra = sorted(set(got) - {p["number"] for p in PLAN})
    res = {"planned": len(PLAN), "found": len(got), "extra_numbers": extra, "problems": problems,
           "states": {k: sum(1 for g in got.values() if ("merged" if g.get("merged") else g["state"].lower()) == k) for k in ("open", "closed", "merged")},
           "comments": sum(g["comments"]["totalCount"] for g in got.values())}
    json.dump(res, open(os.path.join(ROOT, "analysis", "github-migration-verification.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps({k: (v if k != "problems" else v[:30]) for k, v in res.items()}, ensure_ascii=False, indent=1), "problems:", len(problems))

