"""아직 만들지 않은 항목의 원본 댓글을 본문 '원본 댓글' 절로 합쳐 생성 요청 수를 줄인다 (GitHub 콘텐츠 생성 한도 시간당 500건 대응).

본문 한도(65,536자)를 넘는 댓글만 개별 댓글로 남긴다. 이미 생성된 항목은 건드리지 않는다.
"""
import json
import os
import sys

WORK = sys.argv[1]
LIMIT = 64000
plan = json.load(open(os.path.join(WORK, "plan.json"), encoding="utf-8"))
state = json.load(open(os.path.join(WORK, "state.json"), encoding="utf-8"))["items"]
folded = kept = 0
for p in plan:
    if "created" in state.get(str(p["number"]), {}) or not p["comments"] or p.get("comments_folded"):
        continue
    head = f"\n\n---\n\n## 원본 댓글 ({len(p['comments'])})\n"
    body, rest = p["body"] + head, []
    for c in p["comments"]:
        piece = "\n\n" + c + "\n\n---"
        if not rest and len(body) + len(piece) <= LIMIT:
            body += piece
            folded += 1
        else:
            rest.append(c)
    if rest:
        body += f"\n\n_(나머지 {len(rest)}개는 본문 길이 한도로 아래 댓글에 이어진다)_"
    p["body"], p["comments"], p["comments_folded"] = body, rest, True
    kept += len(rest)
json.dump(plan, open(os.path.join(WORK, "plan.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("folded", folded, "kept as comments", kept)

