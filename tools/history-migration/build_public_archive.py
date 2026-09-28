"""공개 저장소용 project-history/ 를 원본 덤프에서 만든다: 개인 이메일·계정 ID·아바타·웹훅 등을 걷어낸 검색 가능한 기록.

원본(raw)은 수정하지 않는다. 사용법: python build_public_archive.py <migration 루트> <출력 project-history 디렉터리>
"""
import collections
import glob
import hashlib
import json
import os
import re
import shutil
import sys
from datetime import datetime, timedelta, timezone

ROOT, OUT = sys.argv[1], sys.argv[2]
NEW = "https://github.com/" + os.environ.get("TARGET_REPO", "mingleland/ssafesta")
JI = os.path.join(ROOT, "raw", "jira", "dump-20260928")
GL = os.path.join(ROOT, "raw", "gitlab", "api-20260928")
GH = os.path.join(ROOT, "raw", "github-original", "api-20260928")
KST = timezone(timedelta(hours=9))
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
PHONE = re.compile(r"\b01[016789][- .]?\d{3,4}[- .]?\d{4}\b")
DROP_KEYS = {"email", "emailAddress", "public_email", "avatar_url", "avatarUrls", "avatar", "commit_email", "author_email",
             "committer_email", "ip_address", "gravatar", "_links", "self"}
mapping = json.load(open(os.path.join(ROOT, "work", "mapping.json"), encoding="utf-8"))
new_of = {x["original"]: (x["new_number"], x["new_kind"]) for x in mapping["items"]}
people = json.load(open(os.path.join(ROOT, "tools", "people.json"), encoding="utf-8"))
jira_users = {}
for f in glob.glob(os.path.join(JI, "api", "users", "*.json")):
    u = json.load(open(f, encoding="utf-8"))
    jira_users[u["accountId"]] = u.get("displayName")
stats = collections.Counter()


def jl(p):
    return json.load(open(p, encoding="utf-8"))


def pages(d, key=None):
    out = []
    for f in sorted(glob.glob(os.path.join(d, "page-*.json"))):
        v = jl(f)
        out += (v.get(key, []) if key else v) if isinstance(v, dict) else v
    return out


def clean_text(s):
    if not isinstance(s, str):
        return s
    s2 = EMAIL.sub(lambda m: m.group(0) if m.group(0).endswith("noreply.github.com") else "[email]", s)
    s2 = PHONE.sub("[phone]", s2)
    if s2 != s:
        stats["text_masked"] += 1
    return s2


def clean(o):
    if isinstance(o, dict):
        out = {}
        for k, v in o.items():
            if k in DROP_KEYS:
                continue
            if k == "accountId":
                out["user"] = jira_users.get(v, "unknown")
                continue
            if k == "url" and isinstance(v, str) and "api-private.atlassian.com/automation/webhooks" in v:
                out[k] = "[webhook-url-removed]"
                continue
            out[k] = clean(v)
        return out
    if isinstance(o, list):
        return [clean(x) for x in o]
    return clean_text(o)


def w(rel, obj=None, text=None):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        if text is not None:
            f.write(text)
        else:
            json.dump(obj, f, ensure_ascii=False, indent=1)


def wl(rel, rows):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def kst(s):
    if not s:
        return "-"
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(KST).strftime("%Y-%m-%d %H:%M KST")


# ---------- ADF → Markdown ----------
def adf(node, ctx=None):
    if node is None:
        return ""
    if isinstance(node, str):
        return node
    t = node.get("type")
    c = node.get("content") or []
    kids = lambda sep="": sep.join(adf(x, ctx) for x in c)
    a = node.get("attrs") or {}
    if t == "doc":
        return "\n\n".join(adf(x, ctx) for x in c).strip()
    if t == "paragraph":
        return kids()
    if t == "text":
        s = node.get("text", "")
        for m in node.get("marks") or []:
            mt = m.get("type")
            if mt == "code":
                s = f"\x60{s}\x60"
            elif mt == "strong":
                s = f"**{s}**"
            elif mt == "em":
                s = f"*{s}*"
            elif mt == "strike":
                s = f"~~{s}~~"
            elif mt == "link":
                s = f"[{s}]({(m.get('attrs') or {}).get('href', '')})"
        return s
    if t == "hardBreak":
        return "  \n"
    if t == "heading":
        return "#" * int(a.get("level", 1)) + " " + kids()
    if t in ("bulletList", "orderedList"):
        out = []
        for i, li in enumerate(c, 1):
            body = "\n\n".join(adf(x, ctx) for x in li.get("content") or [])
            prefix = f"{i}. " if t == "orderedList" else "- "
            lines = body.split("\n")
            out.append(prefix + lines[0] + "".join("\n   " + l for l in lines[1:]))
        return "\n".join(out)
    if t == "taskList":
        return "\n".join(adf(x, ctx) for x in c)
    if t == "taskItem":
        return ("- [x] " if a.get("state") == "DONE" else "- [ ] ") + kids()
    if t == "codeBlock":
        return f"\x60\x60\x60{a.get('language') or ''}\n" + "".join(x.get("text", "") for x in c) + "\n\x60\x60\x60"
    if t in ("blockquote", "panel"):
        inner = "\n\n".join(adf(x, ctx) for x in c)
        tag = f"**[{a.get('panelType', 'info')}]** " if t == "panel" else ""
        return "\n".join("> " + (tag if i == 0 else "") + l for i, l in enumerate(inner.split("\n")))
    if t == "rule":
        return "---"
    if t == "mention":
        return "\x60" + (a.get("text") or "@user") + "\x60"
    if t == "emoji":
        return a.get("text") or a.get("shortName", "")
    if t in ("inlineCard", "blockCard", "embedCard"):
        return a.get("url") or ""
    if t in ("mediaSingle", "mediaGroup", "mediaInline"):
        return "\n".join(adf(x, ctx) for x in c) if c else f"[첨부: {a.get('alt') or a.get('id')}]"
    if t == "media":
        return f"[첨부: {a.get('alt') or a.get('id', '')}]"
    if t == "status":
        return f"[{a.get('text', '')}]"
    if t == "date":
        try:
            return datetime.fromtimestamp(int(a.get("timestamp")) / 1000, KST).strftime("%Y-%m-%d")
        except Exception:
            return str(a.get("timestamp"))
    if t in ("expand", "nestedExpand"):
        return f"<details><summary>{a.get('title') or '펼치기'}</summary>\n\n" + "\n\n".join(adf(x, ctx) for x in c) + "\n\n</details>"
    if t == "table":
        rows = []
        for r in c:
            rows.append([adf(cell, ctx).replace("\n", "<br>").replace("|", "\\|") for cell in r.get("content") or []])
        if not rows:
            return ""
        n = max(len(r) for r in rows)
        rows = [r + [""] * (n - len(r)) for r in rows]
        return "\n".join(["| " + " | ".join(rows[0]) + " |", "|" + "---|" * n] + ["| " + " | ".join(r) + " |" for r in rows[1:]])
    if t in ("tableRow", "tableCell", "tableHeader", "listItem", "decisionList", "decisionItem", "layoutSection", "layoutColumn"):
        return "\n\n".join(adf(x, ctx) for x in c)
    stats[f"adf_unknown_{t}"] += 1
    return kids()


def fmt_val(v, names=None):
    if v is None:
        return None
    if isinstance(v, dict):
        if v.get("type") == "doc":
            return adf(v)
        for k in ("displayName", "name", "value", "key"):
            if k in v:
                return str(v[k])
        return json.dumps(clean(v), ensure_ascii=False)
    if isinstance(v, list):
        vals = [fmt_val(x) for x in v]
        return ", ".join(x for x in vals if x) or None
    return str(v)


def build_jira():
    names = None
    rows = []
    links_by_key = {x["key"]: x for x in jl(os.path.join(ROOT, "analysis", "jira-links.json"))}
    keys = [x["key"] for f in sorted(glob.glob(os.path.join(JI, "api", "search", "page-*.json"))) for x in jl(f)["issues"]]
    index = []
    for k in keys:
        d = os.path.join(JI, "api", "issues", k)
        iss = jl(os.path.join(d, "issue.v3.json"))
        names = iss.get("names") or names or {}
        f = iss["fields"]
        comments = pages(os.path.join(d, "comments"), "comments")
        hist = pages(os.path.join(d, "changelog"), "values")
        link = links_by_key.get(k, {})
        prs = [f"[!{m}]({NEW}/{'pull' if new_of.get(f'GitLab !{m}', (0, 'pr'))[1] == 'pr' else 'issues'}/{new_of[f'GitLab !{m}'][0]})"
               for m in link.get("gitlab_mrs", []) if f"GitLab !{m}" in new_of]
        gl_is = [f"[GitLab #{m}]({NEW}/issues/{new_of[f'GitLab #{m}'][0]})" for m in link.get("gitlab_issues", []) if f"GitLab #{m}" in new_of]
        md = [f"# {k} · {clean_text(f['summary'])}", "",
              "| 필드 | 값 |", "|---|---|"]
        def row(label, val):
            if val not in (None, "", []):
                md.append(f"| {label} | {str(val).replace('|', '\\|').replace(chr(10), '<br>')} |")
        row("유형", f["issuetype"]["name"])
        row("상태", f["status"]["name"] + (f" (해결: {f['resolution']['name']})" if f.get("resolution") else ""))
        row("우선순위", (f.get("priority") or {}).get("name"))
        row("보고자", (f.get("reporter") or {}).get("displayName"))
        row("담당자", (f.get("assignee") or {}).get("displayName"))
        if f.get("parent"):
            row("상위", f"[{f['parent']['key']}]({f['parent']['key']}.md) {clean_text(f['parent']['fields'].get('summary', ''))}")
        row("하위 작업", ", ".join(f"[{s['key']}]({s['key']}.md)" for s in f.get("subtasks") or []))
        row("라벨", ", ".join(f.get("labels") or []))
        row("컴포넌트", fmt_val(f.get("components")))
        row("생성", kst(f["created"]))
        row("수정", kst(f.get("updated")))
        row("해결", kst(f.get("resolutiondate")) if f.get("resolutiondate") else None)
        row("기한", f.get("duedate"))
        row("GitLab MR", ", ".join(prs))
        row("GitLab 이슈", ", ".join(gl_is))
        row("커밋", f"{len(link.get('commits', []))}건 (develop 반영 {sum(1 for c in link.get('commits', []) if c['in_develop'])}건)" if link.get("commits") else None)
        row("브랜치", ", ".join(f"\x60{b}\x60" for b in link.get("branches", [])))
        std = {"summary", "issuetype", "status", "resolution", "priority", "reporter", "assignee", "parent", "subtasks", "labels", "components",
               "created", "updated", "resolutiondate", "duedate", "description", "comment", "attachment", "issuelinks", "worklog", "watches",
               "votes", "creator", "project", "lastViewed", "statuscategorychangedate", "timetracking", "aggregateprogress", "progress",
               "workratio", "issuerestriction", "thumbnail", "statusCategory"}
        extra = []
        for fk, fv in sorted(f.items()):
            if fk in std or fv in (None, [], ""):
                continue
            val = fmt_val(fv)
            if val and fk.startswith("customfield_") and names.get(fk) in ("Rank", "개발"):
                continue
            if val:
                extra.append((names.get(fk, fk), clean_text(val)))
        for label, val in extra:
            row(label, val[:500])
        md += ["", "## 설명", "", clean_text(adf(f.get("description"))) or "_(없음)_"]
        if f.get("issuelinks"):
            md += ["", "## 이슈 링크", ""]
            for l in f["issuelinks"]:
                other = l.get("outwardIssue") or l.get("inwardIssue")
                rel = l["type"]["outward"] if l.get("outwardIssue") else l["type"]["inward"]
                md.append(f"- {rel}: [{other['key']}]({other['key']}.md) {clean_text(other['fields'].get('summary', ''))}")
        if f.get("attachment"):
            md += ["", "## 첨부", ""]
            for at in f["attachment"]:
                md.append(f"- [{at['filename']}](../attachments/{k}/{at['id']}/{at['filename']}) ({at['size']} bytes, {at.get('mimeType')}, {kst(at['created'])}, {(at.get('author') or {}).get('displayName')})")
        remote = jl(os.path.join(d, "remotelinks.json")) if os.path.exists(os.path.join(d, "remotelinks.json")) else []
        if remote:
            md += ["", "## 원격 링크", ""] + [f"- {r['object'].get('title')}: {r['object'].get('url')}" for r in remote]
        md += ["", f"## 댓글 ({len(comments)})", ""]
        for c in comments:
            md += [f"### {(c.get('author') or {}).get('displayName')} · {kst(c['created'])}" + (f" (수정 {kst(c['updated'])})" if c.get("updated", "")[:16] != c["created"][:16] else ""), "",
                   clean_text(adf(c.get("body"))), ""]
        md += [f"## 변경 이력 ({len(hist)})", "", "| 시각 | 작성자 | 필드 | 이전 | 이후 |", "|---|---|---|---|---|"]
        for h in hist:
            for it in h.get("items", []):
                cell = lambda s: clean_text((s or "").replace("|", "\\|").replace("\n", " "))[:300]
                md.append(f"| {kst(h['created'])} | {(h.get('author') or {}).get('displayName', '-')} | {it.get('field')} | {cell(it.get('fromString'))} | {cell(it.get('toString'))} |")
        w(f"jira/issues/{k}.md", text="\n".join(md) + "\n")
        rec = clean({"key": k, "id": iss["id"], "fields": {fk: fv for fk, fv in f.items() if fk not in ("comment", "worklog")},
                     "comments": comments, "changelog": hist, "remotelinks": remote})
        rows.append(rec)
        index.append((k, f["issuetype"]["name"], f["status"]["name"], (f.get("assignee") or {}).get("displayName") or "-", clean_text(f["summary"]), f["created"][:10]))
        stats["jira_md"] += 1
    wl("jira/issues.jsonl", rows)
    idx = ["# Jira S15P21A604 이슈 목록", "", f"총 {len(index)}건. 각 행은 이슈별 Markdown 기록으로 연결된다.", "",
           "| 키 | 유형 | 상태 | 담당자 | 생성일 | 제목 |", "|---|---|---|---|---|---|"]
    idx += [f"| [{k}](issues/{k}.md) | {t} | {s} | {a} | {c} | {x.replace('|', '\\|')} |" for k, t, s, a, x, c in index]
    w("jira/README.md", text="\n".join(idx) + "\n")
    meta = {}
    for rel in ("project.json", "project-components.json", "project-versions.json", "project-statuses.json", "fields.json",
                "priorities.json", "resolutions.json", "issueLinkTypes.json", "issuetypes-project.json"):
        p = os.path.join(JI, "api", "project", rel)
        if os.path.exists(p):
            meta[rel[:-5]] = clean(jl(p))
    meta["boards"] = [clean(jl(p)) for p in glob.glob(os.path.join(JI, "api", "agile", "board-*", "configuration.json"))]
    meta["sprints"] = [clean(jl(p)) for p in sorted(glob.glob(os.path.join(JI, "api", "agile", "sprint-*", "sprint.json")))]
    w("jira/project-metadata.json", meta)
    for p in glob.glob(os.path.join(JI, "attachments", "*", "*", "*")):
        rel = os.path.relpath(p, os.path.join(JI, "attachments"))
        dst = os.path.join(OUT, "jira", "attachments", rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(p, dst)
        stats["jira_attachments_copied"] += 1


def build_gitlab():
    mrs, notes = [], []
    for f in sorted(glob.glob(os.path.join(GL, "api", "merge_requests", "*", "merge_request.json")), key=lambda p: int(p.split(os.sep)[-2])):
        iid = int(f.split(os.sep)[-2])
        d = os.path.dirname(f)
        m = jl(f)
        m["_commits"] = [c["id"] for c in pages(os.path.join(d, "commits"))]
        m["_approvals"] = jl(os.path.join(d, "approvals.json")) if os.path.exists(os.path.join(d, "approvals.json")) else None
        m["_reviewers"] = jl(os.path.join(d, "reviewers.json")) if os.path.exists(os.path.join(d, "reviewers.json")) else None
        m["_versions"] = pages(os.path.join(d, "versions"))
        m["_state_events"] = pages(os.path.join(d, "resource_state_events"))
        m["_label_events"] = pages(os.path.join(d, "resource_label_events"))
        m["_new_github"] = dict(zip(("number", "kind"), new_of.get(f"GitLab !{iid}", (None, None))))
        mrs.append(clean(m))
        for dsc in pages(os.path.join(d, "discussions")):
            for n in dsc["notes"]:
                notes.append(clean({"noteable": f"!{iid}", "discussion_id": dsc["id"], **n}))
    iss = []
    for i in pages(os.path.join(GL, "api", "project", "issues")):
        d = os.path.join(GL, "api", "issues", str(i["iid"]))
        i["_state_events"] = pages(os.path.join(d, "resource_state_events"))
        i["_label_events"] = pages(os.path.join(d, "resource_label_events"))
        i["_links"] = jl(os.path.join(d, "links.json")) if os.path.exists(os.path.join(d, "links.json")) else None
        i["_new_github"] = dict(zip(("number", "kind"), new_of.get(f"GitLab #{i['iid']}", (None, None))))
        iss.append(clean(i))
        for dsc in pages(os.path.join(d, "discussions")):
            for n in dsc["notes"]:
                notes.append(clean({"noteable": f"#{i['iid']}", "discussion_id": dsc["id"], **n}))
    cn = []
    for dd in glob.glob(os.path.join(GL, "api", "commits", "*", "discussions")):
        for dsc in pages(dd):
            for n in dsc["notes"]:
                cn.append(clean({"noteable": "commit:" + dd.split(os.sep)[-2], "discussion_id": dsc["id"], **n}))
    wl("gitlab/merge_requests.jsonl", mrs)
    wl("gitlab/issues.jsonl", iss)
    wl("gitlab/notes.jsonl", notes)
    wl("gitlab/commit_notes.jsonl", cn)
    wl("gitlab/events.jsonl", [clean(e) for e in pages(os.path.join(GL, "api", "project", "events"))])
    pipes = []
    for pf in sorted(glob.glob(os.path.join(GL, "api", "pipelines", "*", "pipeline.json"))):
        p = jl(pf)
        jobs = pages(os.path.join(os.path.dirname(pf), "jobs"))
        pipes.append(clean({k: p.get(k) for k in ("id", "iid", "sha", "ref", "status", "source", "created_at", "started_at", "finished_at", "duration", "user", "web_url")}
                           | {"jobs": [{k: j.get(k) for k in ("id", "name", "stage", "status", "created_at", "finished_at", "duration", "allow_failure", "failure_reason")} for j in jobs]}))
    wl("gitlab/pipelines.jsonl", pipes)
    proj = jl(os.path.join(GL, "api", "project", "project.json"))
    w("gitlab/project.json", clean({k: proj.get(k) for k in ("id", "name", "path_with_namespace", "description", "default_branch", "created_at",
                                                            "last_activity_at", "web_url", "statistics", "topics", "visibility")}))
    w("gitlab/labels.json", clean(pages(os.path.join(GL, "api", "project", "labels"))))
    w("gitlab/members.json", [{"username": m["username"], "name": m["name"], "access_level": m["access_level"]}
                              for m in pages(os.path.join(GL, "api", "project", "members"))])
    up = jl(os.path.join(GL, "uploads-manifest.json"))
    for r in up["by_reference"]:
        if r.get("file"):
            dst = os.path.join(OUT, "gitlab", "uploads", r["secret"], r["filename"])
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(os.path.join(GL, r["file"]), dst)
            stats["gitlab_uploads_copied"] += 1
    w("gitlab/uploads-index.json", [{"secret": r["secret"], "filename": r["filename"], "bytes": r.get("bytes"), "sha256": r.get("sha256"),
                                      "upload_id": r.get("upload_id")} for r in up["by_reference"]])


def build_github():
    items = pages(os.path.join(GH, "api", "repo", "issues-and-pulls"))
    out, comments = [], []
    for i in items:
        n = i["number"]
        if "pull_request" in i:
            p = jl(os.path.join(GH, "api", "pulls", str(n), "pull.json"))
            i["_pull"] = {k: p.get(k) for k in ("merged_at", "merge_commit_sha", "merged_by", "head", "base", "commits", "additions", "deletions", "changed_files")}
            i["_reviews"] = pages(os.path.join(GH, "api", "pulls", str(n), "reviews"))
            i["_commits"] = [c["sha"] for c in pages(os.path.join(GH, "api", "pulls", str(n), "commits"))]
        i["_timeline"] = [e for e in pages(os.path.join(GH, "api", "issues", str(n), "timeline")) if e.get("event") not in ("commented",)]
        i["_new_github"] = dict(zip(("number", "kind"), new_of.get(f"GitHub #{n}", (None, None))))
        for k in ("body_html", "body_text"):
            i.pop(k, None)
        out.append(clean(i))
        for c in pages(os.path.join(GH, "api", "issues", str(n), "comments")):
            c.pop("body_html", None)
            c.pop("body_text", None)
            comments.append(clean({"issue": n, **c}))
    wl("github-original/issues_and_pulls.jsonl", out)
    wl("github-original/comments.jsonl", comments)
    w("github-original/labels.json", clean(pages(os.path.join(GH, "api", "repo", "labels"))))


build_jira()
build_gitlab()
build_github()
for rel in ("mapping.json",):
    shutil.copy2(os.path.join(ROOT, "work", rel), os.path.join(OUT, "mappings", "items.json") if os.makedirs(os.path.join(OUT, "mappings"), exist_ok=True) is None else None)
w("mappings/jira-links.json", [clean(x) for x in jl(os.path.join(ROOT, "analysis", "jira-links.json"))])
shutil.copy2(os.path.join(ROOT, "analysis", "gitlab-mr-jira-keys.json"), os.path.join(OUT, "mappings", "gitlab-mr-jira-keys.json"))
shutil.copy2(os.path.join(ROOT, "analysis", "gitlab-issue-jira-keys.json"), os.path.join(OUT, "mappings", "gitlab-issue-jira-keys.json"))
shutil.copy2(os.path.join(ROOT, "analysis", "gitlab-mr-merge-analysis.json"), os.path.join(OUT, "mappings", "gitlab-mr-merge-analysis.json"))
print(json.dumps(stats, ensure_ascii=False))

