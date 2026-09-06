#!/usr/bin/env python3
"""festa-flow — 작업 의도를 어느 artifact 에 둘지 정한다.

입력은 문장이 아니라 **카드**다. "이슈"·"티켓"·"issue" 같은 단어는 입력에 없다 —
단어로 provider 를 고르다가 GitLab Issue 가 필요한 자리에 Jira Task 를 만든 것이
이 파일이 존재하는 이유다(2026-09-05 G-8 · 50~84초 · -416 BE 요청).

카드 필드 (전부 문맥을 읽고 사람/Agent 가 채운다):
  needs_other_part_answer  다른 파트가 읽고 답·확정·결정해야 진행되는가
  my_implementation_work   내가 구현·추적할 실제 업무인가
  code_change_ready        제출할 코드 변경이 있는가
  existing                 선조회 결과 {jira: [...], gitlab_issue: [...], mr: [...]}
  phrase                   원문 (참고용. 판정에 쓰지 않는다)

출력: 필요한 artifact 마다 하나씩 {artifact, action, target?}
  artifact  JIRA | GITLAB_ISSUE | MR
  action    ATTACH | CREATE | ASK_CREATE | ASK_WHICH
            (Jira 는 사람이 만든다 — 팀 규칙 1. 그래서 CREATE 가 아니라 ASK_CREATE)
카드가 셋 다 False 면 artifact 를 하나도 내지 않고 AMBIGUOUS 를 돌려준다 — 그때는
만들지 말고 문맥을 더 읽거나 사용자에게 **artifact 종류만** 묻는다.
"""
import json
import sys

JIRA, GITLAB_ISSUE, MR = "JIRA", "GITLAB_ISSUE", "MR"


def _pick(candidates, create_action):
    if len(candidates) == 1:
        return {"action": "ATTACH", "target": candidates[0]}
    if len(candidates) > 1:
        return {"action": "ASK_WHICH", "candidates": list(candidates)}
    return {"action": create_action}


def route(card):
    existing = card.get("existing") or {}
    out = []
    # 순서가 곧 규칙이다. Jira Task 가 있다는 사실은 GitLab Issue 를 생략할 이유가 아니다 (N6).
    if card.get("needs_other_part_answer"):
        out.append({"artifact": GITLAB_ISSUE, **_pick(existing.get("gitlab_issue") or [], "CREATE")})
    if card.get("my_implementation_work"):
        out.append({"artifact": JIRA, **_pick(existing.get("jira") or [], "ASK_CREATE")})
    if card.get("code_change_ready"):
        out.append({"artifact": MR, **_pick(existing.get("mr") or [], "CREATE")})
    if not out:
        return {"verdict": "AMBIGUOUS", "decisions": [], "ask": "구현 Task(Jira)인가, 타 파트와 조율할 GitLab Issue 인가?"}
    return {"verdict": "ROUTED", "decisions": out}


def _creates(result, artifact):
    return [d for d in result["decisions"] if d["artifact"] == artifact and d["action"] in ("CREATE", "ASK_CREATE")]


def _has(result, artifact):
    return [d for d in result["decisions"] if d["artifact"] == artifact]


def self_test():
    # N1 cross-part 요청 → Jira create 0
    r = route({"needs_other_part_answer": True, "phrase": "BE 에 계약 확인 요청을 별도 이슈로 남겨"})
    assert _has(r, GITLAB_ISSUE) and not _creates(r, JIRA) and not _has(r, JIRA), r
    # N2 구현 Task → GitLab Issue create 0
    r = route({"my_implementation_work": True, "phrase": "이 구현 작업을 내 Task 로 등록"})
    assert _has(r, JIRA) and not _has(r, GITLAB_ISSUE), r
    # N3 code review/merge 요청 → Jira/GitLab Issue create 0
    r = route({"code_change_ready": True, "phrase": "리뷰 올려"})
    assert _has(r, MR) and not _has(r, JIRA) and not _has(r, GITLAB_ISSUE), r
    # N4 "issue" 라는 단어만 있음 → 단어로 provider 를 고르지 않는다 (phrase 는 판정 입력이 아니다)
    a = route({"needs_other_part_answer": True, "phrase": "issue 만들어"})
    b = route({"needs_other_part_answer": True, "phrase": "Jira 이슈 만들어"})
    c = route({"needs_other_part_answer": True, "phrase": ""})
    assert a["decisions"] == b["decisions"] == c["decisions"], (a, b, c)
    assert route({"phrase": "issue 만들어"})["verdict"] == "AMBIGUOUS"
    assert route({"phrase": "Jira 이슈 만들어"})["verdict"] == "AMBIGUOUS"
    # N5 기존 GitLab coordination Issue 존재 → 새 Jira Task 로 대체 0
    r = route({"needs_other_part_answer": True, "existing": {"gitlab_issue": ["#133"]}})
    assert _has(r, GITLAB_ISSUE)[0]["action"] == "ATTACH" and not _has(r, JIRA), r
    # N6 기존 Jira Task 존재 + cross-part 필요 → Jira 존재를 이유로 GitLab Issue 생략 0
    r = route({"needs_other_part_answer": True, "my_implementation_work": True, "existing": {"jira": ["S15P21A604-416"]}})
    assert _has(r, GITLAB_ISSUE)[0]["action"] == "CREATE", r
    assert _has(r, JIRA)[0]["action"] == "ATTACH" and _has(r, JIRA)[0]["target"] == "S15P21A604-416", r
    # 후보 여럿 → 임의 선택·생성 0
    r = route({"needs_other_part_answer": True, "existing": {"gitlab_issue": ["#1", "#2"]}})
    assert _has(r, GITLAB_ISSUE)[0]["action"] == "ASK_WHICH", r
    print("self-test ok: N1 N2 N3 N4 N5 N6 + ambiguous-candidates")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
    else:
        print(json.dumps(route(json.load(sys.stdin)), ensure_ascii=False, indent=2))
