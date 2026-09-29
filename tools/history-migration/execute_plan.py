"""plan.json 을 새 GitHub 저장소에 실제 이슈·PR 로 만든다. 재시작해도 이어서 진행하는 실행기 (Phase D).

사용법: python execute_plan.py <work 디렉터리> <owner/repo> [--limit N] [--dry-run]
규칙: 계획 번호와 실제 번호가 다르면 즉시 멈춘다. 콘텐츠 생성 요청은 7.5초 간격(시간당 480건)으로 보낸다.
상태는 state.json 에 항목·단계별로 기록한다. 병합 표시는 git push(실제 병합 커밋)로 따로 한다.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

WORK, REPO = sys.argv[1], sys.argv[2]
LIMIT = int(sys.argv[sys.argv.index("--limit") + 1]) if "--limit" in sys.argv else None
DRY = "--dry-run" in sys.argv
TOKEN = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
PLAN = json.load(open(os.path.join(WORK, "plan.json"), encoding="utf-8"))
STATE_P = os.path.join(WORK, "state.json")
STATE = json.load(open(STATE_P, encoding="utf-8")) if os.path.exists(STATE_P) else {"items": {}, "labels_done": False}
LOG = open(os.path.join(WORK, "execute.log"), "a", encoding="utf-8")
CREATE_GAP = float(os.environ.get("CREATE_GAP", "7.5"))
OTHER_GAP = float(os.environ.get("OTHER_GAP", "1.0"))
_last = {"create": 0.0, "other": 0.0}


def log(*a):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + " ".join(str(x) for x in a)
    print(line, flush=True)
    LOG.write(line + "\n")
    LOG.flush()


def save():
    tmp = STATE_P + ".tmp"
    json.dump(STATE, open(tmp, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for attempt in range(20):  # Windows 백신·색인기가 잠깐 파일을 잡으면 replace 가 실패한다
        try:
            os.replace(tmp, STATE_P)
            return
        except PermissionError:
            time.sleep(0.5)
    raise SystemExit("state.json 저장 실패")


def api(method, path, body=None, kind="other"):
    gap = CREATE_GAP if kind == "create" else OTHER_GAP
    wait = _last[kind] + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    if DRY:
        log("DRY", method, path, (body or {}).get("title", "")[:60])
        _last[kind] = time.time()
        return 200, {"number": None}
    for attempt in range(12):
        req = urllib.request.Request("https://api.github.com" + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                                              "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "mingleland-history-migration"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                _last[kind] = time.time()
                data = r.read()
                return r.status, (json.loads(data) if data else None)
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", "replace")
            _last[kind] = time.time()
            if e.code in (403, 429) and ("rate limit" in payload.lower() or e.headers.get("Retry-After")):
                ra = float(e.headers.get("Retry-After") or 0)
                reset = e.headers.get("X-RateLimit-Reset")
                sleep = ra or (max(60, int(reset) - time.time() + 5) if reset and e.headers.get("X-RateLimit-Remaining") == "0" else 120 * (attempt + 1))
                log("rate limited", e.code, path, "sleep", int(sleep))
                time.sleep(sleep)
                continue
            if e.code >= 500:
                time.sleep(10 * (attempt + 1))
                continue
            return e.code, payload
        except Exception as e:
            log("network error", repr(e))
            time.sleep(15 * (attempt + 1))
    raise SystemExit(f"giving up on {method} {path}")


def ensure_labels():
    if STATE.get("labels_done"):
        return
    names = sorted({l for p in PLAN for l in p["labels"]})
    colors = json.load(open(os.path.join(WORK, "label-colors.json"), encoding="utf-8")) if os.path.exists(os.path.join(WORK, "label-colors.json")) else {}
    for n in names:
        st, res = api("POST", f"/repos/{REPO}/labels", {"name": n, "color": colors.get(n, "ededed").lstrip("#")})
        if st not in (200, 201) and "already_exists" not in str(res):
            raise SystemExit(f"label {n}: {st} {res}")
    STATE["labels_done"] = True
    save()
    log("labels", len(names))


def create(p, s):
    if "created" in s:
        return
    if p["kind"] == "pr" and not s.get("fallback_issue"):
        st, res = api("POST", f"/repos/{REPO}/pulls", {"title": p["title"], "body": p["body"], "head": p["head_ref"], "base": p["base_ref"],
                                                       "maintainer_can_modify": False}, "create")
        if st == 422 and "No commits between" in str(res):
            log("no commits between -> issue", p["number"])
            s["fallback_issue"] = True
            save()
            return create(p, s)
    else:
        body = p["body"]
        if s.get("fallback_issue"):
            body = "> PR 로 만들 수 없어(커밋 차이 없음) 이슈로 남긴다.\n\n" + body
        st, res = api("POST", f"/repos/{REPO}/issues", {"title": p["title"], "body": body, "labels": p["labels"]}, "create")
    if st not in (200, 201):
        raise SystemExit(f"create failed #{p['number']}: {st} {str(res)[:500]}")
    got = res["number"] if not DRY else p["number"]
    s["created"] = got
    s["kind"] = "issue" if (p["kind"] == "issue" or s.get("fallback_issue")) else "pr"
    save()
    if got != p["number"]:
        raise SystemExit(f"NUMBER MISMATCH planned {p['number']} got {got} — stop")
    log("created", s["kind"], got, p["original_id"])


def run():
    ensure_labels()
    done = 0
    for p in PLAN:
        key = str(p["number"])
        s = STATE["items"].setdefault(key, {})
        if s.get("complete"):
            continue
        if LIMIT is not None and done >= LIMIT:
            break
        create(p, s)
        n = s["created"]
        if s["kind"] == "pr" and not s.get("labeled"):
            st, res = api("POST", f"/repos/{REPO}/issues/{n}/labels", {"labels": p["labels"]})
            if st not in (200, 201):
                raise SystemExit(f"label failed #{n}: {st} {res}")
            s["labeled"] = True
            save()
        k = s.get("comments_done", 0)
        for c in p["comments"][k:]:
            st, res = api("POST", f"/repos/{REPO}/issues/{n}/comments", {"body": c}, "create")
            if st not in (200, 201):
                raise SystemExit(f"comment failed #{n}: {st} {str(res)[:300]}")
            k += 1
            s["comments_done"] = k
            save()
        final = p["final_state"] if not s.get("fallback_issue") else "closed"
        if final == "closed" and not s.get("closed"):
            body = {"state": "closed"}
            if s["kind"] == "issue":
                body["state_reason"] = p.get("close_reason") or "completed"
                path = f"/repos/{REPO}/issues/{n}"
            else:
                path = f"/repos/{REPO}/pulls/{n}"
            st, res = api("PATCH", path, body)
            if st != 200:
                raise SystemExit(f"close failed #{n}: {st} {res}")
            s["closed"] = True
            save()
        s["complete"] = True
        save()
        done += 1
        if done % 25 == 0:
            log("progress", n, "/", PLAN[-1]["number"])
    log("run finished; complete items:", sum(1 for v in STATE["items"].values() if v.get("complete")))


if __name__ == "__main__":
    run()

