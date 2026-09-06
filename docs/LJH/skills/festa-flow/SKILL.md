---
name: festa-flow
description: SSAFY FESTA 에서 작업 의도를 어느 artifact 에 둘지 정한다 — Jira Task(내 업무) / GitLab Issue(타 파트가 읽고 답할 것) / MR(코드). "이슈 만들어", "별도 이슈로 남겨", "다른 파트에 요청해", "계약 확인 받아", "Task 로 등록해", "리뷰 올려" 같은 요청이 오면 무엇을 만들기 전에 이 skill 을 먼저 거친다. 단어("이슈"·"티켓")로 provider 를 고르지 않는다.
---

# festa-flow — 무엇을 어디에 둘 것인가

**한 문장 규칙**: "이슈"라는 단어는 아무것도 결정하지 않는다. 결정하는 것은 세 질문이다.

이 팀에서 같은 사고가 세 번 났다(2026-09-05). "별도 이슈로 분리해라"를 Jira Task 생성으로만
처리해 **타 파트가 읽을 자리를 만들지 않았다** — G-8 입력 잠금은 개인 문서에만 5일, -416 의
BE 요청은 Jira 코멘트에만 하루. 원인은 팀 공용 문서(AGENTS·CLAUDE·workflow)가 "이슈"를 Jira 의
뜻으로만 쓰고, GitLab Issue 의 역할은 어디에도 적혀 있지 않았다는 것이다.

## 0. 정본 분류 (바꾸지 않는다)

| artifact | 뜻 | 근거 |
|---|---|---|
| **Jira Task** | 내가 구현·추적하는 실제 업무. 목적·담당·범위·완료조건·MR·검증 | `docs/18` §1, `AGENTS.md` 규칙 1 |
| **GitLab Issue** | 타 파트가 **읽고 답해야** 하는 것 — 질문·계약 확정·결정 요청·blocker 전달. 제목 `[part,part] 주제 — 요청/통보 (KEY)` | 팀 실사용(#127~#135), `CLAUDE.local.md` 경계 절 |
| **MR** | 코드 변경·리뷰·병합 | `docs/17` §6~§9 |

Jira 코멘트는 communication surface 가 **아니다** — 그 파트가 볼 자리가 없다.

## 1. 카드를 채운다 (단어가 아니라 문맥으로)

```text
needs_other_part_answer  다른 파트가 읽고 답·확정·결정해야 진행되는가?
my_implementation_work   내가 구현·추적할 실제 업무인가?
code_change_ready        제출할 코드 변경이 있는가?
```

셋은 배타가 아니다. **Jira Task 가 있어도 다른 파트의 답이 필요하면 GitLab Issue 는 따로 필요하다.**
셋 다 아니면 만들지 않는다 — 문맥을 더 읽고, 그래도 모르면 사용자에게 **artifact 종류만** 묻는다:

> "이건 구현 Task 로 Jira 에 남길까요, 아니면 BE 와 조율할 GitLab Issue 로 남길까요?"

## 2. 기존 artifact 를 먼저 찾는다

```bash
# Task 목적          → JAM
jira_search  project = S15P21A604 AND summary ~ "<주제>" ORDER BY updated DESC
# cross-part 목적    → GitLab Issue
glab issue list --all --search "<주제>"
# code change        → MR
glab mr list --all --search "<주제>"
```

찾은 것을 카드의 `existing` 에 넣는다. **적합한 것이 하나면 붙이고, 없으면 만들고, 여럿이면 만들지 않는다.**

## 3. 판정은 코드가 한다

```bash
python3 ~/.claude/skills/festa-flow/route.py <<'JSON'
{ "needs_other_part_answer": true, "my_implementation_work": true,
  "existing": { "jira": ["S15P21A604-416"], "gitlab_issue": [] },
  "phrase": "BE 에 계약 확인 요청을 별도 이슈로 남겨" }
JSON
```

`phrase` 는 기록용이고 판정에 쓰이지 않는다. 회귀는 `python3 route.py --self-test` (N1~N6).

| 판정 | 뜻 |
|---|---|
| `GITLAB_ISSUE / CREATE` | 팀 형식으로 만든다 (아래 4) |
| `GITLAB_ISSUE / ATTACH #n` | 그 이슈에 코멘트로 잇는다. 새로 만들지 않는다 |
| `JIRA / ATTACH KEY` | 그 Task 아래에서 작업한다 |
| `JIRA / ASK_CREATE` | **사람이 만든다** — 키를 예측·선사용하지 않는다(AGENTS 규칙 1, LJH T-키 사고 2건) |
| `MR / CREATE` | `docs/17` §6 제목 + `.gitlab/merge_request_templates/Default.md` 골격 |
| `ASK_WHICH` | 후보가 여럿. 고르지 않는다 |
| `AMBIGUOUS` | 종류를 묻는다 |

## 4. GitLab Issue 를 만들 때의 팀 형식 (실측 #127~#135)

```text
제목   [part,part] 주제 — 확정 요청 / 구현 통보 / 결함 N건 (S15P21A604-N, #관련)
라벨   관련 파트 라벨(front/back/game/ai/infra) + 성격(question/blocker/bug)
본문   첫 줄 @담당자 호명 → 배경 → 요청(번호) → `관련 Jira` · `MR` 블록 (양방향 링크)
담당   답해야 할 사람을 Assignee 로
```

**결정 요청**이면 하나 더 — `docs/26_팀_결정_필요사항.md` 에 미결 행을 등록한다(`AGENTS.md`: "결정이 없어서 막힘 → docs/26 등록 + 리드에게 알림"). GitLab Issue 는 알림이고 docs/26 은 팀의 결정 장부다. 둘 다 있어야 한다.

```bash
glab issue create --title "<제목>" --label front,back --assignee <user> --description-file <파일>
```

만든 뒤 ASC 가 있으면 증거를 잇는다 — 판정은 여기서 끝났고 실행·되읽기·증거는 ASC 몫이다:

```bash
asc coordination publish --query <X-ID> --title "<제목>" --body-file <파일> \
  --known 's15-metaverse-game-sub1/S15P21A604#<iid>' --work '<KEY>' --audience <part>
```

## 5. 하지 않는 것

- "이슈"·"티켓"·"issue" 단어로 Jira/GitLab 을 고르지 않는다 (N4)
- Jira Task 존재를 이유로 GitLab Issue 를 생략하지 않는다 (N6 — 최초 사고)
- Jira 코멘트로 타 파트에 요청하지 않는다
- 없는 Jira 키를 예측하지 않는다. Jira 생성은 사람 경계다
- 후보가 여럿이거나 카드가 비면 만들지 않는다

## 6. 관계

`festa-inbox` 는 나를 기다리는 것을, `festa-outbox` 는 내가 기다리는 것을 본다. 이 skill 은 그 앞 —
**만들기 전에** 종류를 정한다. ASC 의 generic coordination(discover·attach·read-back·evidence)은 그대로
쓰고, 그 안에 Jira·GitLab·SSAFESTA 분기를 넣지 않는다.
