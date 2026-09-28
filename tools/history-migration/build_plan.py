"""새 GitHub 저장소에 만들 PR·이슈의 전체 계획을 원본 덤프에서 결정적으로 생성한다 (Phase D 준비, 오프라인).

번호 규칙: #1~#84 는 최초 GitHub 번호를 그대로 쓰고(GitLab #1~#83 은 그 이관 사본이라 합친다),
#85 부터는 GitLab 이슈(사본 제외)와 MR 을 생성 시각 순으로 배치한다.
출력: plan.json(항목별 제목·본문·댓글·라벨·상태·브랜치), mapping.json(원본 ID ↔ 새 번호), refspecs.txt(선행 push 목록)
"""
import collections
import glob
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

ROOT, OUT = sys.argv[1], sys.argv[2]
ORG_REPO = os.environ.get("TARGET_REPO", "mingleland/ssafesta")
DEFAULT_BRANCH = "develop"
NEW = f"https://github.com/{ORG_REPO}"
RAW_BASE = f"{NEW}/raw/{DEFAULT_BRANCH}"
GL = os.path.join(ROOT, "raw", "gitlab", "api-20260928")
GLA = os.path.join(GL, "api")
GHA = os.path.join(ROOT, "raw", "github-original", "api-20260928", "api")
REPO = os.path.join(ROOT, "raw", "gitlab", "S15P21A604-complete.git")
GH_REPO = os.path.join(ROOT, "raw", "github-original", "ssafesta.git")
GL_WEB = "https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604"
GH_WEB = "https://github.com/kanghyunsoon/ssafesta"
KST = timezone(timedelta(hours=9))
IMPORT_CUTOFF = datetime(2026, 8, 24, 10, 45, tzinfo=KST)  # GitLab #1~#83 이관 스크립트 실행 직후
BODY_LIMIT = 60000
os.makedirs(OUT, exist_ok=True)


def jl(p):
    return json.load(open(p, encoding="utf-8"))


def pages(d):
    out = []
    for f in sorted(glob.glob(os.path.join(d, "page-*.json"))):
        v = jl(f)
        out += v if isinstance(v, list) else [v]
    return out


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def kst(s):
    return ts(s).astimezone(KST).strftime("%Y-%m-%d %H:%M KST") if s else "-"


PEOPLE = jl(os.path.join(ROOT, "tools", "people.json"))
BY_GL = {p["gitlab"]: p for p in PEOPLE}
BY_GH = {p["github"].lower(): p for p in PEOPLE}


def who_gl(u):
    if not u:
        return "-"
    p = BY_GL.get(u["username"])
    if p:
        return f"{p['name']} (GitLab \x60{u['username']}\x60 · GitHub [{p['github']}](https://github.com/{p['github']}))"
    return f"{u.get('name') or u['username']} (GitLab \x60{u['username']}\x60)"


def who_gh(login):
    if not login:
        return "-"
    p = BY_GH.get(login.lower())
    if p:
        return f"{p['name']} (GitHub [{p['github']}](https://github.com/{p['github']}))"
    return f"GitHub \x60{login}\x60"


# ---------- 원본 적재 ----------
gh_items = {i["number"]: i for i in pages(os.path.join(GHA, "repo", "issues-and-pulls"))}
gh_pulls = {n: jl(os.path.join(GHA, "pulls", str(n), "pull.json")) for n, i in gh_items.items() if "pull_request" in i}
gl_issues = {i["iid"]: i for i in pages(os.path.join(GLA, "project", "issues"))}
gl_mrs = {m["iid"]: jl(os.path.join(GLA, "merge_requests", str(m["iid"]), "merge_request.json")) for m in pages(os.path.join(GLA, "project", "merge_requests"))}
uploads = jl(os.path.join(GL, "uploads-manifest.json"))
upload_files = {(u["secret"], u["filename"]): u for u in uploads["by_reference"] if u.get("file")}
COPY_RE = re.compile(r"^> \*\*GitHub 이관\*\* — 원본: https://github\.com/kanghyunsoon/ssafesta/(?:issues|pull)/(\d+)")
gl_copy_of = {}
for iid, i in gl_issues.items():
    m = COPY_RE.match(i.get("description") or "")
    if m:
        gl_copy_of[int(m.group(1))] = iid

# ---------- 번호 배정 ----------
items = []
for n in sorted(gh_items):
    items.append({"number": n, "origin": "github", "id": n})
rest = [("gitlab-issue", iid, i["created_at"]) for iid, i in gl_issues.items() if iid not in gl_copy_of.values()]
rest += [("gitlab-mr", iid, m["created_at"]) for iid, m in gl_mrs.items()]
rest.sort(key=lambda x: (ts(x[2]), x[0], x[1]))
nxt = max(gh_items) + 1
for kind, iid, _ in rest:
    items.append({"number": nxt, "origin": kind, "id": iid})
    nxt += 1
map_gh = {it["id"]: it["number"] for it in items if it["origin"] == "github"}
map_gl_issue = {it["id"]: it["number"] for it in items if it["origin"] == "gitlab-issue"}
for ghn, gliid in gl_copy_of.items():
    map_gl_issue[gliid] = map_gh[ghn]
map_gl_mr = {it["id"]: it["number"] for it in items if it["origin"] == "gitlab-mr"}


# ---------- 본문 변환 ----------
CODE_SPLIT = re.compile(r"(\x60\x60\x60[\s\S]*?(?:\x60\x60\x60|$)|~~~[\s\S]*?(?:~~~|$)|\x60[^\x60\n]+\x60)")
MENTION = re.compile(r"(?<![\w/@.\x60])@([A-Za-z0-9_][A-Za-z0-9_.\-]*[A-Za-z0-9_])")
GL_URL = re.compile(re.escape(GL_WEB) + r"/-/(issues|merge_requests)/(\d+)(#note_\d+)?")
GH_URL = re.compile(r"https?://github\.com/kanghyunsoon/ssafesta/(issues|pull)/(\d+)(#[\w-]+)?")
GH_SHORT = re.compile(r"kanghyunsoon/ssafesta#(\d+)")
UPLOAD = re.compile(r"(?:" + re.escape(GL_WEB) + r"|https://lab\.ssafy\.com/-/project/\d+|/-/project/\d+)?/uploads/([0-9a-f]{32})/([^\s\)\]\"'<>]+)")
GL_ISSUE_REF = re.compile(r"(?<![\w/&#!\[])#(\d+)\b")
GL_MR_REF = re.compile(r"(?<![\w/&!\[])!(\d+)\b")
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
REDACT = jl(os.path.join(ROOT, "tools", "redactions.json")) if os.path.exists(os.path.join(ROOT, "tools", "redactions.json")) else []
stats = collections.Counter()


def link(kind, num):
    return f"{NEW}/{'pull' if kind == 'pr' else 'issues'}/{num}"


def new_kind(num):
    return PLAN_KIND.get(num, "issue")


def sub_upload(m):
    key = (m.group(1), m.group(2))
    import urllib.parse
    name = urllib.parse.unquote(m.group(2))
    if (m.group(1), name) in upload_files or key in upload_files:
        stats["upload_links"] += 1
        return f"{RAW_BASE}/project-history/gitlab/uploads/{m.group(1)}/{m.group(2)}"
    stats["upload_links_unresolved"] += 1
    return m.group(0)


def transform(text, origin):
    if not text:
        return ""
    for r in REDACT:
        if r["secret_fingerprint_text"] in text:
            text = text.replace(r["secret_fingerprint_text"], "[REDACTED]")
            stats["redacted"] += 1
    parts = CODE_SPLIT.split(text)
    for i in range(0, len(parts), 2):
        t = parts[i]
        t = UPLOAD.sub(sub_upload, t)
        t = GH_URL.sub(lambda m: f"[원본 GitHub #{m.group(2)}]({link('x', map_gh.get(int(m.group(2)), int(m.group(2))))})", t)
        t = GH_SHORT.sub(lambda m: f"#{m.group(1)}", t)
        def gl_url(m):
            n = int(m.group(2))
            if m.group(1) == "issues" and n in map_gl_issue:
                return f"[GitLab #{n}]({link('issue', map_gl_issue[n])})"
            if m.group(1) == "merge_requests" and n in map_gl_mr:
                return f"[GitLab !{n}]({link('pr', map_gl_mr[n])})"
            return m.group(0)
        t = GL_URL.sub(gl_url, t)
        if origin == "gitlab":
            t = GL_MR_REF.sub(lambda m: f"[!{m.group(1)}]({link('pr', map_gl_mr[int(m.group(1))])})" if int(m.group(1)) in map_gl_mr else m.group(0), t)
            t = GL_ISSUE_REF.sub(lambda m: f"[GitLab #{m.group(1)}]({link('issue', map_gl_issue[int(m.group(1))])})" if int(m.group(1)) in map_gl_issue else m.group(0), t)
        t = MENTION.sub(lambda m: f"\x60@{m.group(1)}\x60", t)
        t = EMAIL.sub(lambda m: m.group(0) if m.group(0).endswith("noreply.github.com") else "[email]", t)
        parts[i] = t
    return "".join(parts)


def quote_block(lines):
    return "\n".join("> " + l if l else ">" for l in lines)


def activity(lines):
    if not lines:
        return ""
    return "\n\n<details><summary>원본 활동 기록 (" + str(len(lines)) + "건)</summary>\n\n" + "\n".join(lines) + "\n\n</details>\n"


def sys_line(n, origin="gitlab"):
    body = transform(n["body"], origin).replace("\n", " ⏎ ")
    return f"- {kst(n['created_at'])} · {who_gl(n['author'])} · {body}"


def gl_labels(obj):
    return [l["name"] if isinstance(l, dict) else l for l in obj.get("labels") or []]


def comment_from_gl_note(n, thread_note=""):
    head = f"**{who_gl(n['author'])}** · {kst(n['created_at'])} · GitLab note \x60{n['id']}\x60{thread_note}"
    if n.get("updated_at") and n["updated_at"][:16] != n["created_at"][:16]:
        head += f" · 수정 {kst(n['updated_at'])}"
    return f"> {head}\n\n{transform(n['body'], 'gitlab')}"


def gl_thread_comments(kind, iid, after=None):
    """GitLab discussion 을 시간순 댓글로 편다. 답글은 어느 스레드의 답인지 표시한다."""
    out, sysl = [], []
    for d in pages(os.path.join(GLA, kind, str(iid), "discussions")):
        first = None
        for k, n in enumerate(d["notes"]):
            if after and ts(n["created_at"]) <= after:
                continue
            if n["system"]:
                sysl.append((n["created_at"], sys_line(n)))
                continue
            note = ""
            if not d.get("individual_note") and k > 0:
                note = f" · 스레드 답글 (첫 note \x60{d['notes'][0]['id']}\x60)"
            if d.get("individual_note") is False and n.get("resolvable"):
                note += " · 해결됨" if n.get("resolved") else ""
            out.append((n["created_at"], comment_from_gl_note(n, note)))
    out.sort()
    sysl.sort()
    return [c for _, c in out], [l for _, l in sysl]


def split_body(header, main, act):
    body = header + "\n\n---\n\n" + (main or "_(원본 본문 없음)_") + act
    if len(body) <= BODY_LIMIT:
        return body, []
    extra = []
    body = header + "\n\n---\n\n" + (main or "")
    if len(body) > BODY_LIMIT:
        cut = body[BODY_LIMIT - 200:]
        body = body[:BODY_LIMIT - 200] + "\n\n_(본문이 길어 이어지는 내용은 아래 댓글에 있다)_"
        extra += ["> 본문 이어짐\n\n" + cut[i:i + BODY_LIMIT] for i in range(0, len(cut), BODY_LIMIT)]
    if act:
        extra += ["> 원본 활동 기록 (본문 길이 제한으로 분리)\n\n" + act[i:i + BODY_LIMIT] for i in range(0, len(act), BODY_LIMIT)]
    stats["split_bodies"] += 1
    return body, extra


def git_ok(repo, *a):
    return subprocess.run(["git", "-C", repo, *a], capture_output=True).returncode == 0


PLAN_KIND = {}
plan = []
refspecs = []
merge_analysis = {r["iid"]: r for r in jl(os.path.join(ROOT, "analysis", "gitlab-mr-merge-analysis.json"))}

# ---------- #1~#84 최초 GitHub ----------
for n in sorted(gh_items):
    it = gh_items[n]
    is_pr = "pull_request" in it
    copy_iid = gl_copy_of.get(n)
    gl_copy = gl_issues.get(copy_iid) if copy_iid else None
    comments = []
    for c in pages(os.path.join(GHA, "issues", str(n), "comments")):
        comments.append((c["created_at"], f"> **{who_gh(c['user']['login'])}** · {kst(c['created_at'])} · 원본 GitHub comment \x60{c['id']}\x60\n\n{transform(c['body'], 'github')}"))
    act = []
    for e in pages(os.path.join(GHA, "issues", str(n), "timeline")):
        ev = e.get("event")
        if ev in (None, "commented", "committed", "reviewed", "mentioned", "subscribed"):
            continue
        actor = (e.get("actor") or e.get("user") or {}).get("login")
        detail = ""
        if ev in ("labeled", "unlabeled"):
            detail = e["label"]["name"]
        elif ev == "renamed":
            detail = f"{e['rename']['from']} → {e['rename']['to']}"
        elif ev == "cross-referenced":
            src = (e.get("source") or {}).get("issue") or {}
            detail = f"#{src.get('number')} {src.get('title', '')}"
        elif ev in ("closed", "merged", "referenced") and e.get("commit_id"):
            detail = f"commit \x60{e['commit_id'][:10]}\x60"
        elif ev in ("review_requested", "assigned", "unassigned"):
            detail = ((e.get("requested_reviewer") or e.get("assignee") or {}) or {}).get("login", "")
        act.append((e.get("created_at") or "", f"- {kst(e.get('created_at'))} · {who_gh(actor)} · {ev} {transform(detail, 'github')}".rstrip()))
    if is_pr:
        p = gh_pulls[n]
        for rv in pages(os.path.join(GHA, "pulls", str(n), "reviews")):
            comments.append((rv["submitted_at"], f"> **{who_gh(rv['user']['login'])}** · {kst(rv['submitted_at'])} · 원본 GitHub review \x60{rv['id']}\x60 · **{rv['state']}**\n\n{transform(rv.get('body') or '', 'github') or '_(리뷰 본문 없음)_'}"))
    gl_extra, gl_sys = [], []
    if gl_copy:
        gl_extra, gl_sys = gl_thread_comments("issues", copy_iid, after=IMPORT_CUTOFF)
        comments += [(re.search(r"(\d{4}-\d\d-\d\d \d\d:\d\d)", c).group(1), c.replace("> **", "> **[GitLab 이관본 추가 댓글]** **", 1)) for c in gl_extra]
    comments.sort(key=lambda x: x[0])
    lines = [f"**최초 GitHub {'PR' if is_pr else '이슈'} 이전 기록** · 원본 \x60kanghyunsoon/ssafesta#{n}\x60 (비공개 저장소, \x60{GH_WEB}/{'pull' if is_pr else 'issues'}/{n}\x60)",
             f"작성: {who_gh(it['user']['login'])} · 생성 {kst(it['created_at'])}" + (f" · 닫힘 {kst(it['closed_at'])}" if it.get("closed_at") else "")]
    labels = ["source: github-" + ("pr" if is_pr else "issue")] + [l["name"] for l in it.get("labels", [])]
    state = it["state"]
    item = {"number": n, "origin": "github", "original_id": f"GitHub #{n}", "kind": "pr" if is_pr else "issue", "title": it["title"]}
    if is_pr:
        p = gh_pulls[n]
        merged = bool(p.get("merged_at"))
        lines.append(f"상태: **{'merged' if merged else p['state']}**" + (f" {kst(p['merged_at'])} by {who_gh((p.get('merged_by') or {}).get('login'))}" if merged else "")
                     + f" · \x60{p['head']['ref']}\x60 → \x60{p['base']['ref']}\x60 · head \x60{p['head']['sha'][:10]}\x60 · base \x60{p['base']['sha'][:10]}\x60"
                     + (f" · 병합 커밋 \x60{p['merge_commit_sha'][:10]}\x60" if merged and p.get("merge_commit_sha") else ""))
        rr = [u["login"] for u in p.get("requested_reviewers", [])]
        if rr or p.get("assignees"):
            lines.append("리뷰 요청: " + (", ".join(who_gh(x) for x in rr) or "-") + " · 담당: " + (", ".join(who_gh(a["login"]) for a in p.get("assignees", [])) or "-"))
        head_ok = git_ok(GH_REPO, "cat-file", "-e", p["head"]["sha"] + "^{commit}")
        can_pr = head_ok and p["head"]["sha"] != p["base"]["sha"] and not git_ok(GH_REPO, "merge-base", "--is-ancestor", p["head"]["sha"], p["base"]["sha"])
        item.update(head_ref=f"gh-pr/{n}/head", head_sha=p["head"]["sha"], base_ref=f"gh-pr/{n}/base", base_sha=p["base"]["sha"])
        if merged:
            final = "merged" if (p.get("merge_commit_sha") and git_ok(GH_REPO, "merge-base", "--is-ancestor", p["head"]["sha"], p["merge_commit_sha"])) else "closed"
            item["merge_push_sha"] = p["merge_commit_sha"] if final == "merged" else None
            if final == "closed":
                lines.append("원본에서는 병합되었지만 병합 방식(squash 등) 때문에 head 커밋이 병합 커밋의 조상이 아니어서, 이 PR 은 GitHub 에서 Closed 로 남긴다. 실제 병합 결과는 위 병합 커밋이다.")
                labels.append("original: merged")
        else:
            final = p["state"]
        if not can_pr:
            item["kind"] = "issue"
            lines.append("head 와 base 사이에 커밋 차이가 없어 PR 객체로 만들 수 없어 이슈로 남긴다.")
            stats["pr_as_issue"] += 1
    else:
        final = state
    if gl_copy:
        lines.append(f"GitLab 이관 사본: \x60GitLab #{copy_iid}\x60 (\x60{GL_WEB}/-/issues/{copy_iid}\x60) · 사본 상태 **{gl_copy['state']}**"
                     + (f" {kst(gl_copy['closed_at'])}" if gl_copy.get("closed_at") else ""))
        labels.append("gitlab-copy")
        if final == "open" and gl_copy["state"] == "closed":
            final = "closed"
            lines.append("GitLab 사본이 나중에 닫혔으므로 최종 상태를 Closed 로 둔다.")
    lines.append("> 이 항목은 이전 도구가 원본 기록으로 다시 만든 것이다. 작성자·시각은 원본 값이며, GitHub 가 표시하는 작성자·시각은 이전 시점이다.")
    act_lines = [l for _, l in sorted(act)] + gl_sys
    body, extra = split_body(quote_block(lines), transform(it.get("body") or "", "github"), activity(act_lines))
    item.update(body=body, comments=extra + [c for _, c in comments], labels=sorted(set(labels)), final_state=final,
                close_reason="completed" if final == "closed" and not is_pr else None, created_at=it["created_at"])
    PLAN_KIND[n] = item["kind"]
    plan.append(item)

# ---------- #85~ GitLab ----------
for it in items:
    if it["origin"] == "github":
        continue
    n, iid = it["number"], it["id"]
    if it["origin"] == "gitlab-issue":
        g = gl_issues[iid]
        comments, sysl = gl_thread_comments("issues", iid)
        lines = [f"**GitLab 이슈 이전 기록** · 원본 \x60GitLab #{iid}\x60 (\x60{GL_WEB}/-/issues/{iid}\x60)",
                 f"작성: {who_gl(g['author'])} · 생성 {kst(g['created_at'])}" + (f" · 닫힘 {kst(g['closed_at'])} by {who_gl(g.get('closed_by'))}" if g.get("closed_at") else ""),
                 "담당: " + (", ".join(who_gl(a) for a in g.get("assignees", [])) or "-") + " · 라벨: " + (", ".join(gl_labels(g)) or "-")]
        lines.append("> 이 항목은 이전 도구가 원본 기록으로 다시 만든 것이다. 작성자·시각은 원본 값이며, GitHub 가 표시하는 작성자·시각은 이전 시점이다.")
        body, extra = split_body(quote_block(lines), transform(g.get("description"), "gitlab"), activity(sysl))
        final = "closed" if g["state"] == "closed" else "open"
        plan.append({"number": n, "origin": "gitlab-issue", "original_id": f"GitLab #{iid}", "kind": "issue", "title": g["title"], "body": body,
                     "comments": extra + comments, "labels": sorted({"source: gitlab-issue", *gl_labels(g)}), "final_state": final,
                     "close_reason": "completed" if final == "closed" else None, "created_at": g["created_at"]})
        PLAN_KIND[n] = "issue"
    else:
        m = gl_mrs[iid]
        a = merge_analysis[iid]
        comments, sysl = gl_thread_comments("merge_requests", iid)
        appr = jl(os.path.join(GLA, "merge_requests", str(iid), "approvals.json")) if os.path.exists(os.path.join(GLA, "merge_requests", str(iid), "approvals.json")) else {}
        revs = jl(os.path.join(GLA, "merge_requests", str(iid), "reviewers.json")) if os.path.exists(os.path.join(GLA, "merge_requests", str(iid), "reviewers.json")) else []
        lines = [f"**GitLab MR 이전 기록** · 원본 \x60!{iid}\x60 (\x60{GL_WEB}/-/merge_requests/{iid}\x60)",
                 f"작성: {who_gl(m['author'])} · 생성 {kst(m['created_at'])}",
                 f"상태: **{m['state']}**" + (f" {kst(m['merged_at'])} by {who_gl(m.get('merge_user') or m.get('merged_by'))}" if m.get("merged_at") else "")
                 + (f" · 닫힘 {kst(m['closed_at'])} by {who_gl(m.get('closed_by'))}" if m.get("closed_at") else "")
                 + f" · \x60{m['source_branch']}\x60 → \x60{m['target_branch']}\x60",
                 f"head \x60{(a['head'] or '')[:10]}\x60 · base \x60{(a['base'] or '')[:10]}\x60"
                 + (f" · 병합 커밋 \x60{m['merge_commit_sha'][:10]}\x60" if m.get("merge_commit_sha") else "")
                 + (f" · squash 커밋 \x60{m['squash_commit_sha'][:10]}\x60" if m.get("squash_commit_sha") else ""),
                 "리뷰어: " + (", ".join(who_gl(r["user"]) + f" ({r.get('state')})" for r in revs) or "-")
                 + " · 승인: " + (", ".join(who_gl(x["user"]) for x in appr.get("approved_by", [])) or "-")
                 + " · 담당: " + (", ".join(who_gl(x) for x in m.get("assignees", [])) or "-"),
                 "라벨: " + (", ".join(gl_labels(m)) or "-") + f" · 커밋 {m.get('changes_count') or '-'} 파일 변경"]
        can_pr = bool(a["head"] and a["base"]) and not a["base_eq_head"] and not a["head_ancestor_of_base"]
        final = "closed"
        merge_push = None
        if m["state"] == "merged":
            if a["head_in_merge_commit"] and m.get("merge_commit_sha"):
                final, merge_push = "merged", m["merge_commit_sha"]
            else:
                lines.append("원본에서는 병합되었지만 squash 병합이라 head 커밋이 병합 커밋의 조상이 아니므로, 이 PR 은 GitHub 에서 Closed 로 남긴다. 실제 병합 결과는 위 병합·squash 커밋이다.")
        kind = "pr"
        if not can_pr:
            kind = "issue"
            lines.append("head 와 base 사이에 커밋 차이가 없어 PR 객체로 만들 수 없어 이슈로 남긴다.")
            stats["mr_as_issue"] += 1
        lines.append("> 이 항목은 이전 도구가 원본 기록으로 다시 만든 것이다. 작성자·시각은 원본 값이며, GitHub 가 표시하는 작성자·시각과 병합 주체는 이전 시점·이전 계정이다.")
        body, extra = split_body(quote_block(lines), transform(m.get("description"), "gitlab"), activity(sysl))
        labels = {"source: gitlab-mr", *gl_labels(m)}
        if m["state"] == "merged" and final != "merged":
            labels.add("original: merged")
        entry = {"number": n, "origin": "gitlab-mr", "original_id": f"GitLab !{iid}", "kind": kind, "title": m["title"], "body": body,
                 "comments": extra + comments, "labels": sorted(labels), "final_state": final if kind == "pr" else "closed",
                 "close_reason": None if kind == "pr" else "completed", "created_at": m["created_at"]}
        if kind == "pr":
            entry.update(head_ref=f"gl-mr/{iid}/head", head_sha=a["head"], base_ref=f"gl-mr/{iid}/base", base_sha=a["base"], merge_push_sha=merge_push)
        plan.append(entry)
        PLAN_KIND[n] = kind

# 선행 push refspec (PR 브랜치)
for p in plan:
    if p["kind"] == "pr":
        refspecs += [f"{p['head_sha']}:refs/heads/{p['head_ref']}", f"{p['base_sha']}:refs/heads/{p['base_ref']}"]
json.dump(plan, open(os.path.join(OUT, "plan.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
open(os.path.join(OUT, "pr-refspecs.txt"), "w", encoding="utf-8").write("\n".join(refspecs) + "\n")
mapping = {"repo": ORG_REPO, "items": [{"new_number": p["number"], "new_kind": p["kind"], "original": p["original_id"], "origin": p["origin"],
                                         "final_state": p["final_state"], "created_at": p["created_at"]} for p in plan],
           "gitlab_issue_copies_of_github": {f"GitLab #{v}": f"GitHub #{k}" for k, v in sorted(gl_copy_of.items())}}
json.dump(mapping, open(os.path.join(OUT, "mapping.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
c = collections.Counter((p["origin"], p["kind"], p["final_state"]) for p in plan)
print(json.dumps({"items": len(plan), "comments": sum(len(p["comments"]) for p in plan), "by": {"|".join(k): v for k, v in sorted(c.items())}, "stats": stats}, ensure_ascii=False, indent=1))

