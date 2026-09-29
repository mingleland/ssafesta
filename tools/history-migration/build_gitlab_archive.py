"""원본 덤프로 GitLab 프로젝트 export archive(0.2.4 ndjson 형식)를 만든다 — 서버 export 가 막혀 GitHub Enterprise Importer 입력을 직접 구성한다.

GitLab 이슈 #1~#83 은 최초 GitHub 이슈·PR 의 이관 사본이므로 GitHub 원본(작성자·댓글·리뷰·커밋)으로 대체한다.
GitHub 로그인은 같은 사람의 GitLab 계정 ID 로 옮겨 mannequin 이 실제 구성원 단위로 생기게 한다.
사용법: python build_gitlab_archive.py <migration 루트> <출력 디렉터리>
"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
GLA = os.path.join(ROOT, "raw", "gitlab", "api-20260928", "api")
GLR = os.path.join(ROOT, "raw", "gitlab", "api-20260928")
GHA = os.path.join(ROOT, "raw", "github-original", "api-20260928", "api")
T = os.path.join(OUT, "tree", "project")
os.makedirs(T, exist_ok=True)


def jl(p):
    return json.load(open(p, encoding="utf-8"))


def pages(d):
    out = []
    for f in sorted(glob.glob(os.path.join(d, "page-*.json"))):
        v = jl(f)
        out += v if isinstance(v, list) else [v]
    return out


def opt(p, default=None):
    return jl(p) if os.path.exists(p) else default


def wl(name, rows):
    with open(os.path.join(T, name), "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


# ---------- 사용자 ----------
users = {}


def see(u):
    if u and u.get("id"):
        users.setdefault(u["id"], {"id": u["id"], "username": u["username"], "name": u.get("name") or u["username"], "public_email": ""})
    return u["id"] if u else None


for m in pages(os.path.join(GLA, "project", "members-all")):
    see(m)
people = jl(os.path.join(ROOT, "tools", "people.json"))
by_username = {u["username"]: uid for uid, u in users.items()}
gh_to_gl = {p["github"].lower(): by_username.get(p["gitlab"]) for p in people}
IMPORTER = by_username["gudtnslwkd"]


def gh_uid(login):
    return gh_to_gl.get((login or "").lower()) or IMPORTER


labels = {l["name"]: l for l in pages(os.path.join(GLA, "project", "labels"))}
for l in pages(os.path.join(GHA, "repo", "labels")):
    labels.setdefault(l["name"], {"name": l["name"], "color": "#" + l["color"], "description": l.get("description")})


def label_links(names, ttype):
    return [{"target_type": ttype, "label": {"title": n, "color": labels.get(n, {}).get("color", "#428BCA"), "description": labels.get(n, {}).get("description"),
                                             "type": "ProjectLabel", "priorities": []}} for n in names]


def gl_notes(kind, iid, noteable, after=None):
    out = []
    for d in pages(os.path.join(GLA, kind, str(iid), "discussions")):
        for n in d["notes"]:
            if after and n["created_at"] <= after:
                continue
            out.append({"note": n["body"], "noteable_type": noteable, "author_id": see(n["author"]), "created_at": n["created_at"],
                        "updated_at": n["updated_at"], "system": n["system"], "discussion_id": d["id"], "type": n.get("type"),
                        "resolved_at": n.get("resolved_at"), "confidential": n.get("confidential") or False, "internal": n.get("internal") or False,
                        "author": {"name": n["author"]["name"]}, "award_emoji": [], "events": [], "suggestions": []})
    return sorted(out, key=lambda x: x["created_at"])


def state_events(kind, iid):
    return [{"user_id": see(e.get("user")), "created_at": e["created_at"], "state": e["state"],
             "source_commit": e.get("source_commit"), "close_after_error_tracking_resolve": False, "close_auto_resolve_prometheus_alert": False}
            for e in pages(os.path.join(GLA, kind, str(iid), "resource_state_events"))]


COPY_RE = re.compile(r"^> \*\*GitHub 이관\*\* — 원본: https://github\.com/kanghyunsoon/ssafesta/(issues|pull)/(\d+)")
gl_issues = pages(os.path.join(GLA, "project", "issues"))
copies = {}
for i in gl_issues:
    m = COPY_RE.match(i.get("description") or "")
    if m:
        copies[int(m.group(2))] = i

issues_out, mrs_out, refmap = [], [], {}
# ---------- GitLab 이슈 (사본 제외) ----------
for i in gl_issues:
    if i in copies.values():
        continue
    issues_out.append({"iid": i["iid"], "title": i["title"], "description": i.get("description") or "", "state": i["state"],
                       "author_id": see(i["author"]), "created_at": i["created_at"], "updated_at": i["updated_at"], "closed_at": i.get("closed_at"),
                       "closed_by_id": see(i.get("closed_by")), "confidential": i.get("confidential", False), "discussion_locked": i.get("discussion_locked"),
                       "notes": gl_notes("issues", i["iid"], "Issue"), "label_links": label_links([l["name"] if isinstance(l, dict) else l for l in i.get("labels") or []], "Issue"),
                       "issue_assignees": [{"user_id": see(a)} for a in i.get("assignees") or []], "resource_state_events": state_events("issues", i["iid"]),
                       "events": [], "timelogs": [], "award_emoji": [], "resource_label_events": [], "resource_milestone_events": []})

# ---------- GitLab MR ----------
for f in sorted(glob.glob(os.path.join(GLA, "merge_requests", "*", "merge_request.json")), key=lambda p: int(p.split(os.sep)[-2])):
    m = jl(f)
    d = os.path.dirname(f)
    iid = m["iid"]
    vers = pages(os.path.join(d, "versions"))
    v = opt(os.path.join(d, "versions", f"{vers[0]['id']}.json")) if vers else None
    dr = m.get("diff_refs") or {}
    commits = (v or {}).get("commits") or pages(os.path.join(d, "commits"))
    diff = {"state": "collected", "base_commit_sha": (v or {}).get("base_commit_sha") or dr.get("base_sha"),
            "head_commit_sha": (v or {}).get("head_commit_sha") or dr.get("head_sha") or m["sha"],
            "start_commit_sha": (v or {}).get("start_commit_sha") or dr.get("start_sha"), "created_at": (v or {}).get("created_at") or m["created_at"],
            "commits_count": len(commits),
            "merge_request_diff_commits": [{"sha": c["id"], "relative_order": k, "message": c.get("message") or c.get("title"),
                                            "authored_date": c.get("authored_date"), "committed_date": c.get("committed_date"),
                                            "commit_author": {"name": c.get("author_name"), "email": c.get("author_email")},
                                            "committer": {"name": c.get("committer_name"), "email": c.get("committer_email")}} for k, c in enumerate(commits)],
            "merge_request_diff_files": [{"relative_order": k, "new_file": x["new_file"], "renamed_file": x["renamed_file"], "deleted_file": x["deleted_file"],
                                          "too_large": x.get("too_large", False), "a_mode": x.get("a_mode"), "b_mode": x.get("b_mode"), "new_path": x["new_path"],
                                          "old_path": x["old_path"], "utf8_diff": x.get("diff") or "", "binary": False} for k, x in enumerate((v or {}).get("diffs") or [])]}
    appr = opt(os.path.join(d, "approvals.json"), {}) or {}
    revs = opt(os.path.join(d, "reviewers.json"), []) or []
    refmap[iid] = diff["head_commit_sha"]
    mrs_out.append({"iid": iid, "title": m["title"], "description": m.get("description") or "", "state": m["state"], "source_branch": m["source_branch"],
                    "target_branch": m["target_branch"], "source_branch_sha": diff["head_commit_sha"], "target_branch_sha": diff["base_commit_sha"],
                    "diff_head_sha": diff["head_commit_sha"], "merge_commit_sha": m.get("merge_commit_sha"), "squash_commit_sha": m.get("squash_commit_sha"),
                    "squash": m.get("squash"), "author_id": see(m["author"]), "created_at": m["created_at"], "updated_at": m["updated_at"],
                    "merge_status": "can_be_merged", "draft": m.get("draft", False), "discussion_locked": m.get("discussion_locked"),
                    "notes": gl_notes("merge_requests", iid, "MergeRequest"), "merge_request_diff": diff,
                    "approvals": [{"user_id": see(a["user"]), "created_at": m.get("merged_at") or m["updated_at"], "updated_at": m["updated_at"]} for a in appr.get("approved_by", [])],
                    "merge_request_reviewers": [{"user_id": see(r["user"]), "created_at": r.get("created_at") or m["created_at"], "state": r.get("state")} for r in revs],
                    "merge_request_assignees": [{"user_id": see(a), "created_at": m["created_at"]} for a in m.get("assignees") or []],
                    "metrics": {"merged_by_id": see(m.get("merge_user") or m.get("merged_by")), "merged_at": m.get("merged_at"),
                                "latest_closed_at": m.get("closed_at"), "latest_closed_by_id": see(m.get("closed_by")), "created_at": m["created_at"], "updated_at": m["updated_at"]},
                    "label_links": label_links([l["name"] if isinstance(l, dict) else l for l in m.get("labels") or []], "MergeRequest"),
                    "resource_state_events": state_events("merge_requests", iid), "events": [], "timelogs": [], "award_emoji": [],
                    "resource_label_events": [], "resource_milestone_events": []})

# ---------- 최초 GitHub 이슈·PR (GitLab 사본 대체) ----------
gh_items = sorted(pages(os.path.join(GHA, "repo", "issues-and-pulls")), key=lambda x: x["number"])
next_mr = max(x["iid"] for x in mrs_out) + 1
gh_map = {}
for it in gh_items:
    n = it["number"]
    cp = copies.get(n)
    notes = [{"note": c["body"] or "", "noteable_type": "PR" if False else None, "author_id": gh_uid(c["user"]["login"]), "created_at": c["created_at"],
              "updated_at": c["updated_at"], "system": False, "discussion_id": None, "type": None, "author": {"name": c["user"]["login"]},
              "award_emoji": [], "events": [], "suggestions": []} for c in pages(os.path.join(GHA, "issues", str(n), "comments"))]
    if cp:  # GitLab 으로 옮긴 뒤 달린 댓글·상태 변경
        notes += [x for x in gl_notes("issues", cp["iid"], None, after=cp["created_at"][:19].replace("T", "T") and "2026-08-24T10:45:00.000+09:00")]
    lbls = [l["name"] for l in it.get("labels", [])]
    if "pull_request" not in it:
        for x in notes:
            x["noteable_type"] = "Issue"
        state = "closed" if (it["state"] == "closed" or (cp and cp["state"] == "closed")) else "opened"
        issues_out.append({"iid": n, "title": it["title"], "description": it.get("body") or "", "state": state, "author_id": gh_uid(it["user"]["login"]),
                           "created_at": it["created_at"], "updated_at": it["updated_at"], "closed_at": it.get("closed_at") or (cp or {}).get("closed_at"),
                           "notes": sorted(notes, key=lambda x: x["created_at"]), "label_links": label_links(lbls, "Issue"),
                           "issue_assignees": [{"user_id": gh_uid(a["login"])} for a in it.get("assignees", [])], "resource_state_events": [],
                           "events": [], "timelogs": [], "award_emoji": [], "resource_label_events": [], "resource_milestone_events": []})
        gh_map[f"GitHub #{n}"] = {"type": "issue", "iid": n}
        continue
    p = jl(os.path.join(GHA, "pulls", str(n), "pull.json"))
    for x in notes:
        x["noteable_type"] = "MergeRequest"
    reviews = pages(os.path.join(GHA, "pulls", str(n), "reviews"))
    notes += [{"note": r.get("body") or f"({r['state']})", "noteable_type": "MergeRequest", "author_id": gh_uid(r["user"]["login"]), "created_at": r["submitted_at"],
               "updated_at": r["submitted_at"], "system": False, "discussion_id": None, "type": None, "author": {"name": r["user"]["login"]},
               "award_emoji": [], "events": [], "suggestions": []} for r in reviews if (r.get("body") or "").strip()]
    commits = pages(os.path.join(GHA, "pulls", str(n), "commits"))
    files = pages(os.path.join(GHA, "pulls", str(n), "files"))
    iid = next_mr
    next_mr += 1
    merged = bool(p.get("merged_at"))
    state = "merged" if merged else ("closed" if (p["state"] == "closed" or (cp and cp["state"] == "closed")) else "opened")
    refmap[iid] = p["head"]["sha"]
    mrs_out.append({"iid": iid, "title": p["title"], "description": p.get("body") or "", "state": state, "source_branch": p["head"]["ref"],
                    "target_branch": p["base"]["ref"], "source_branch_sha": p["head"]["sha"], "target_branch_sha": p["base"]["sha"], "diff_head_sha": p["head"]["sha"],
                    "merge_commit_sha": p.get("merge_commit_sha") if merged else None, "author_id": gh_uid(p["user"]["login"]),
                    "created_at": p["created_at"], "updated_at": p["updated_at"], "merge_status": "can_be_merged", "draft": p.get("draft", False),
                    "notes": sorted(notes, key=lambda x: x["created_at"]),
                    "merge_request_diff": {"state": "collected", "base_commit_sha": p["base"]["sha"], "head_commit_sha": p["head"]["sha"], "start_commit_sha": p["base"]["sha"],
                                           "created_at": p["created_at"], "commits_count": len(commits),
                                           "merge_request_diff_commits": [{"sha": c["sha"], "relative_order": k, "message": c["commit"]["message"],
                                                                           "authored_date": c["commit"]["author"]["date"], "committed_date": c["commit"]["committer"]["date"],
                                                                           "commit_author": {"name": c["commit"]["author"]["name"], "email": c["commit"]["author"]["email"]},
                                                                           "committer": {"name": c["commit"]["committer"]["name"], "email": c["commit"]["committer"]["email"]}}
                                                                          for k, c in enumerate(commits)],
                                           "merge_request_diff_files": [{"relative_order": k, "new_file": f_["status"] == "added", "renamed_file": f_["status"] == "renamed",
                                                                         "deleted_file": f_["status"] == "removed", "too_large": "patch" not in f_, "a_mode": "100644", "b_mode": "100644",
                                                                         "new_path": f_["filename"], "old_path": f_.get("previous_filename") or f_["filename"],
                                                                         "utf8_diff": f_.get("patch") or "", "binary": False} for k, f_ in enumerate(files)]},
                    "approvals": [{"user_id": gh_uid(r["user"]["login"]), "created_at": r["submitted_at"], "updated_at": r["submitted_at"]} for r in reviews if r["state"] == "APPROVED"],
                    "merge_request_reviewers": [{"user_id": gh_uid(r["user"]["login"]), "created_at": r["submitted_at"], "state": "reviewed"} for r in reviews],
                    "merge_request_assignees": [{"user_id": gh_uid(a["login"]), "created_at": p["created_at"]} for a in p.get("assignees", [])],
                    "metrics": {"merged_by_id": gh_uid((p.get("merged_by") or {}).get("login")) if merged else None, "merged_at": p.get("merged_at"),
                                "latest_closed_at": p.get("closed_at"), "created_at": p["created_at"], "updated_at": p["updated_at"]},
                    "label_links": label_links(lbls, "MergeRequest"), "resource_state_events": [], "events": [], "timelogs": [], "award_emoji": [],
                    "resource_label_events": [], "resource_milestone_events": []})
    gh_map[f"GitHub #{n}"] = {"type": "merge_request", "iid": iid}

wl("issues.ndjson", sorted(issues_out, key=lambda x: x["iid"]))
wl("merge_requests.ndjson", sorted(mrs_out, key=lambda x: x["iid"]))
wl("labels.ndjson", [{"title": n, "color": l.get("color", "#428BCA"), "description": l.get("description"), "type": "ProjectLabel", "priorities": []} for n, l in labels.items()])
wl("milestones.ndjson", [])
access = {m["id"]: m["access_level"] for m in pages(os.path.join(GLA, "project", "members-all"))}
wl("project_members.ndjson", [{"access_level": access.get(uid, 30), "source_type": "Project", "user_id": uid, "created_at": "2026-08-24T08:32:26.836+09:00",
                               "user": {"id": uid, "public_email": "", "username": u["username"]}} for uid, u in users.items()])
wl("user_contributions.ndjson", [{"id": uid, "username": u["username"], "name": u["name"], "public_email": ""} for uid, u in users.items()])
proj = jl(os.path.join(GLA, "project", "project.json"))
json.dump({"id": proj["id"], "name": proj["name"], "path": proj["path"], "description": proj.get("description") or "", "visibility_level": 0,
           "archived": False, "default_branch": "develop", "created_at": proj["created_at"], "last_activity_at": proj["last_activity_at"],
           "merge_requests_enabled": True, "issues_enabled": True, "wiki_enabled": False, "snippets_enabled": False, "lfs_enabled": True,
           "squash_option": "default_off", "creator_id": proj.get("creator_id")}, open(os.path.join(OUT, "tree", "project.json"), "w", encoding="utf-8"), ensure_ascii=False)
wl("project_feature.ndjson", [{"builds_access_level": 0, "issues_access_level": 20, "merge_requests_access_level": 20, "forking_access_level": 20,
                               "wiki_access_level": 0, "repository_access_level": 20, "snippets_access_level": 0, "pages_access_level": 0,
                               "created_at": proj["created_at"], "updated_at": proj["last_activity_at"]}])
open(os.path.join(OUT, "VERSION"), "w").write("0.2.4")
open(os.path.join(OUT, "GITLAB_VERSION"), "w").write("18.11.5")
open(os.path.join(OUT, "GITLAB_REVISION"), "w").write("626785d5fa9")
up = jl(os.path.join(GLR, "uploads-manifest.json"))
for r in up["by_reference"]:
    if r.get("file"):
        dst = os.path.join(OUT, "uploads", r["secret"], r["filename"])
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(os.path.join(GLR, r["file"]), dst)
lfs = os.path.join(ROOT, "raw", "gitlab", "S15P21A604.git", "lfs", "objects")
lfs_map = {}
for fp in glob.glob(os.path.join(lfs, "*", "*", "*")):
    oid = os.path.basename(fp)
    os.makedirs(os.path.join(OUT, "lfs-objects"), exist_ok=True)
    shutil.copy2(fp, os.path.join(OUT, "lfs-objects", oid))
    lfs_map[oid] = [None]
json.dump(lfs_map, open(os.path.join(OUT, "lfs-objects.json"), "w"))
json.dump({"mr_heads": refmap, "github_original": gh_map, "users": users}, open(os.path.join(OUT, "..", "archive-build-map.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("issues", len(issues_out), "mrs", len(mrs_out), "users", len(users), "uploads", len(glob.glob(os.path.join(OUT, "uploads", "*", "*"))), "lfs", len(lfs_map))

