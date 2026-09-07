# artifact routing 사고 재구성 — "별도 이슈"가 Jira 로 간 세 번 (2026-09-06)

근본 질문 하나: **이 작업 의도는 어디에 만들어야 하는가.** 정본 분류는 바꾸지 않는다.

```text
Jira / JAM      = Task — 내가 구현·추적하는 실제 업무
GitLab Issue    = 타 파트가 읽고 답해야 하는 것 — 질문·계약 확정·결정 요청·blocker
GitLab MR       = 코드 변경·리뷰·병합
```

실패 유형:

```text
F-A  GitLab Issue 가 필요한데 Jira Task 를 생성/선택
F-B  Jira Task 만 만들고 communication surface 누락
F-C  기존 GitLab Issue 가 있는데 새 Jira Task 로 대체
F-D  "issue"라는 단어를 provider 구분 없이 해석
```

## A. 사고 재구성 (실제 기록에서)

### 사고 1 — G-8 입력 잠금 계약 (08-31 → 09-05, 닷새)

| | |
|---|---|
| 원래 사용자 의도 | Unity 쪽 `captureAllKeyboardInput`·Lock/Unlock 브리지 3건을 **Unity 파트가 확정**해야 FE 오버레이 입력 처리가 정합된다 |
| Agent 가 해석한 의미 | FE 내부 결정 대기 항목 |
| 실제로 만든 artifact | `docs/LJH/ui-design/07_handoff/decision-queue.md` 4행 (개인 문서) + FE 몫 Jira `-428` |
| 원래 만들어야 했던 artifact | GitLab Issue `[game,front] G-8 …` (09-05 에야 #132 로 생성) |
| 왜 잘못 해석됐는가 | "결정 대기"를 **내 대기 목록**으로만 적었다. 상대가 읽을 자리가 있는지 묻지 않았다 |
| 막지 못한 규약 | `AGENTS.md:379` 는 "결정이 없어서 막힘 → docs/26 등록 + 리드에게 알림" 이라 했는데 개인 문서에만 적었다. GitLab Issue 의 역할은 어느 공용 문서에도 없다 |
| 유형 | **F-B** |

### 사고 2 — Lobby→Main 50~84초 · 백그라운드 재접속 (09-05 오전)

| | |
|---|---|
| 원래 사용자 의도 | "별도 이슈로 분리해라" — Unity 병목 계측·Infra 캐시·재접속을 **각 파트에 넘겨** 진행시키기 |
| Agent 가 해석한 의미 | Jira Task 를 4개로 갈라 assignee 를 나눠 주기 |
| 실제로 만든 artifact | Jira `-429`·`-430`·`-431`·`-432` (JAM plan/apply) |
| 원래 만들어야 했던 artifact | GitLab Issue #129(50~84초, Unity·Infra 앞) · #131(재접속) — 같은 날 저녁 지적 뒤 생성 |
| 왜 잘못 해석됐는가 | "이슈" 를 Jira 로 읽었다. 팀 공용 문서가 "이슈" 를 Jira 의 뜻으로만 쓴다(`AGENTS.md:26` "Jira 이슈(S15P21A604-N)", `docs/jira-gitlab-workflow.md:46`) |
| 막지 못한 규약 | Jira Task 에 assignee 를 넣으면 전달됐다고 여겼다. 그 파트가 Jira 코멘트를 읽는다는 보장은 어디에도 없다 |
| 유형 | **F-A + F-D** |

### 사고 3 — -416 BE 계약 요청 2건 (09-04 18:52 → 09-05 20:26, 하루)

| | |
|---|---|
| 원래 사용자 의도 | 요청 payload 의 `handoffSummary` 필드·STOMP 계약 4항을 **BE 가 확정**해 줘야 한다 |
| Agent 가 해석한 의미 | -416 Task 의 진행 기록 |
| 실제로 만든 artifact | Jira `-416` 코멘트 "BE 확인 부탁드릴 것" (09-04 18:52) |
| 원래 만들어야 했던 artifact | GitLab Issue #133 `[back,front] …` (09-05 20:26 생성) |
| 왜 잘못 해석됐는가 | **Jira Task 가 있으니 그 안에 적으면 됐다고 봤다.** Task 존재 ≠ communication surface 존재 |
| 막지 못한 규약 | `docs/18` §1 은 Jira 에 Blocker 를 적으라고만 한다. "상대가 읽는가" 를 묻는 gate 가 없다 |
| 유형 | **F-B (N6 의 원형)** |

### 인접 사고 (routing 이 아님)

- **#130 중복 생성** (09-05): `glab issue create` 성공 URL 형식(`/-/work_items/`)을 실패로 오인해 재실행. 원인은 판정 근거가 주소였다는 것 — ASC 조율 표면이 신원(`project#iid`)으로 고친 그것. 이 문서의 주제가 아니다.
- **festa-outbox 팀 scope 오배치** (09-06, LJH T-41): 위치 사고이지 routing 사고가 아니다.

## B. 기존 규약 정본 조사

| 질문 | 답 | 근거 |
|---|---|---|
| 1. Jira Task 의 역할이 정의돼 있는가 | **있다.** Sprint 작업·담당·진행 상태·Blocker·완료 조건 | `docs/18` §1, §21 Blocked, §30 |
| 2. GitLab Issue 의 역할이 정의돼 있는가 | **공용 문서에는 없다.** 실사용 관행만 있다 — 제목 `[part,part] 주제 — 요청/통보 (KEY)`, 파트 라벨, 첫 줄 @호명, `관련 Jira`·`MR` 블록 (#127~#135 전수). 개인 규칙에는 있다(`CLAUDE.local.md` 경계 절, 09-05) | `glab issue list`, `CLAUDE.local.md` |
| 3. MR 의 역할이 정의돼 있는가 | **있다.** 제목 `[KEY][영역]`, Default 템플릿, squash | `docs/17` §6~§9, `.gitlab/merge_request_templates/Default.md` |
| 4. "issue" 의 provider 해석 규칙이 있는가 | **있는데 반대 방향이다.** 공용 문서는 "이슈" = Jira 로만 쓴다("모든 개발 작업은 Jira 이슈가 선행", "이슈 키"). GitLab Issue 는 한 번도 "이슈" 로 불리지 않는다. 개인 규칙만 "이슈 = GitLab Issue" 로 뒤집어 놓았고, 두 문서가 같은 단어를 반대로 쓴다 | `AGENTS.md:26·33·42`, `docs/jira-gitlab-workflow.md:46·260`, `CLAUDE.local.md` |
| 5. cross-part communication 규칙이 있는가 | **부분.** "결정이 없어서 막힘 → docs/26 등록 + 리드에게 알림, 혼자 정하지 않는다" — 결정 요청만 다룬다. 계약 확인·구현 통보·blocker 전달에는 규칙이 없다 | `AGENTS.md:379` |
| 6. 생성 전 gate 가 있는가 | **Jira 에만.** "이슈 키를 내가 주지 않았다면 Jira 에서 찾고, 없으면 생성 여부를 물어라" — artifact **종류** 를 묻는 gate 는 없다 | `AGENTS.md:26-28`, `CLAUDE.local.md` 작업 시작 Gate |

**결론**: 정본 분류는 이미 있다(1·3). 없는 것은 **GitLab Issue 의 자리(2)와 종류 판정 gate(6)** 이고, 단어 "이슈" 가 공용 문서에서 Jira 만 가리키는 것(4)이 F-D 의 직접 원인이다. 새 SSOT 를 만들지 않고 — 개인 규칙(`CLAUDE.local.md`)에 판정 gate 한 줄을 넣고 그 gate 를 `festa-flow` 가 실행한다. 공용 문서 보정(GitLab Issue 역할 명문화)은 리드 승인 사안이라 여기서 하지 않는다.

## C. 재발 방지 — festa-flow

```text
카드 세 질문 (단어가 아니라 문맥)
  needs_other_part_answer   → GitLab Issue
  my_implementation_work    → Jira (생성은 사람 경계)
  code_change_ready         → MR
선조회 → 하나면 ATTACH · 없으면 CREATE · 여럿이면 만들지 않음
셋 다 아니면 AMBIGUOUS → 종류만 묻는다
```

`~/.claude/skills/festa-flow/route.py --self-test` 가 N1~N6 을 고정한다. 사본은 `docs/LJH/skills/festa-flow/`.

## D. 실전 (2026-09-06)

| 방향 | 카드 | 판정 | 실제 결과 |
|---|---|---|---|
| cross-part | AS-5 운영자 문의 채널 — 리드 결정 필요. 선조회: GitLab 0건, Jira `-433` 있음 | `GITLAB_ISSUE CREATE` + `JIRA ATTACH -433` | GitLab **#136** 생성(front·question, @gudtnslwkd), `docs/26` 미결 행 등록, ASC 증거 `WAITING_EXTERNAL`. **Jira 생성 0** (JAM 전후 최신 키 `-449` 동일) |
| Task | #135 `WORLD_ARCADE_INTERACT` 수신부 구현. 선조회: Jira `-114`(GAME_PORTAL 어댑터, #56) 있음 | `JIRA ATTACH -114` | GitLab Issue 생성 0, Jira 생성 0 — `-114` 아래에서 진행 |

N6 을 실전에서 그대로 밟았다 — `-433` 이 있어도 GitLab Issue 는 따로 만들어졌다.
