# ASC (Agent Session Control) 운영모델 — 최종 설계 v4

> 작성: 2026-08-21. 멀티 AI 세션 운영을 위한 로컬 전용 통제 계층 설계.
> 이 문서는 **설계 확정본**이다. `.asc/` 디렉터리는 아직 생성되지 않았다 — 도입 절차(§9)부터
> 다른 세션이 이 문서만 읽고 이어서 진행할 수 있다.

---

## 0. 한 줄 요약

여러 AI 세션 사이에서 사람이 매번 이전 작업 내용을 기억하고 전달해야 하는 부담을,
`.git/info/exclude`로 제외된 로컬 워크스페이스 `.asc/`가 대신하게 한다.
**자동화가 목적이 아니다** — 병렬 에이전트를 쓰면서도 사람이 프로젝트의 이해·결정권·통제권을
잃지 않는 최소 계약 체계가 목적이다.

## 1. 불변 원칙

1. **기존 SSAFESTA 공식 프로세스 무변경.** `Spec/Docs/Issue 확인 → Block 분해 → 계획 → 구현
   → 검증 → 작업일지 → Issue/PR/팀 공유` 흐름, Speckit 사용법, 작업일지 형식, PR 규약 전부
   그대로. ASC는 그 프로세스 *안에서* AI 세션을 운영하는 방법일 뿐이다.
2. **`.asc/`는 Git에서 완전 분리.** `.git/info/exclude`에 등록 (커밋 0건, 팀에게 안 보임).
   `.asc/`를 통째로 삭제해도 프로젝트에 아무 영향이 없어야 한다.
3. **정본 복제 금지.** spec·API 계약·팀 결정·backlog·작업일지·Issue·PR 내용을 `.asc/`에
   복사하지 않는다. 포인터(`origin/develop:specs/... @ 해시`, `#이슈번호`)로만 참조하고,
   에이전트는 실행 시 정본을 다시 읽는다. `.asc/`가 2차 사본이 되어 drift를 만들면 안 된다.
4. **공식 영역 자동 전파 금지.** 에이전트 산출물이 docs/Issue/PR/작업일지에 자동 반영되는
   경로를 만들지 않는다. 항상 `Handoff/Inbox → Controller 검토 → 필요분만 사람 지시로 반영`.
5. **사람 = Controller.** 우선순위·MVP 범위·정책·spec 변경·파트 간 계약·미결 확정·
   PR/Issue 최종 처리·에이전트 결과 승인은 사람에게 남는다. 에이전트에는 판단이 아니라
   조사·분해·구현·검증의 실행력을 위임한다.
6. **미결은 임의 확정 금지.** 불확실한 사항은 해결하지 말고 Handoff UNRESOLVED로 회수한다.
7. **경계 문장 (운영 철학):** Monitor는 일을 발견하고, 맥락을 조사하고, 상황 설명과 대응
   초안까지 준비한다. Controller는 외부 대응과 실제 작업을 승인한다. Session은 승인된 일을
   수행하고, Verifier는 결과를 독립 검증한다. **외부 게시·전송·상태 변경은 Controller의
   명시적 승인 없이는 절대 수행하지 않는다.**

## 2. 전체 구조 — 3축

```text
                    Controller (사람)
                         │
             ┌───────────┴───────────┐
       Execution Plane         Monitoring Plane
             │                       │
         S-* Session              M-GITHUB
       구현 / 조사 / 검증      감지→조사→분석→초안
             │                       │
          Handoff                  Inbox
             └──────────┬────────────┘
                        ▼
                 Controller 검토
                        │
              Queue 승격 / 게시 지시 / 기각
                        │
                        ▼
                   새 S-* 발급
```

```text
.asc/
├─ ASC.md          # 운영 규칙 + 부트스트랩 절차. 에이전트가 맨 처음 읽는 파일
├─ state.md        # 단일 진실: 활성 Block·세션·최근 Handoff 포인터
├─ controller.md   # Controller 계약 — 사람만 수정
├─ blocks/         # B-NN.md — Block 계약 + 진행 상태
├─ sessions/       # S-YYYYMMDD-NN.md — 세션 계약(상단) + Handoff(하단) 한 파일
└─ monitor/
   ├─ M-GITHUB.md  # Monitor 계약 + cursor
   ├─ inbox.md     # 검토 대기함 (Monitor 쓰기, Controller 처분)
   ├─ queue.md     # 승인된 작업 큐 (Controller 전용)
   └─ log.md       # append-only 감지 이력
```

설계 결정 근거:
- `tasks/` 없음 — Task 정본은 speckit `tasks.md`(공식 영역). 세션 계약에 task 번호만 포인터로.
- `handoffs/`·`verification/` 없음 — Handoff는 세션 파일 하단에 붙인다(계약↔결과 짝 어긋남
  원천 차단). Verifier도 세션이며 산출물은 자기 세션 파일의 Handoff다.
- 세션 50개+ 등으로 비대해지면 그때 분리한다 (이관 비용 = 파일 이동뿐).

## 3. 권한 체계

### 3.1 Green / Yellow / Red

| 등급 | 판정 | 예 |
|---|---|---|
| Green | 에이전트 자율 | 함수/변수명, 내부 파일 분리, 테스트 추가, 명백한 중복 제거, 계약에 영향 없는 구현 상세 |
| Yellow | 변경 금지, 우선 보고 | 공통 모듈 변경, 의존성 추가, 여러 feature에 걸친 구조 변경, 기존 패턴 교체 |
| Red | 즉시 중단, Controller 반환 | spec 요구사항 변경, API 계약 변경, 파트 간(FE/BE/Unity/AI) 계약 변경, 정책 결정, MVP 범위 변경, 타 파트 책임 재정의, 게시·push·merge |

약식 판정: 내 파일 밖 수정 안 생김 = Green / 내 파트 안 파급 = Yellow / 파트 경계 넘음 = Red.
Yellow 사전 허용 목록은 `controller.md`에 세션별로 추가할 수 있다.

### 3.2 Role별 책임 경계

| Role | 하는 것 | 금지 |
|---|---|---|
| Controller (사람) | 우선순위·범위·정책·승인·Queue 확정·세션 발급·게시 지시 | — |
| Planner | Block/Task 분해 초안, 계획 작성 | 공식 작업 생성, 우선순위 확정 |
| Researcher | 조사, 결과 보고 | 조사 결과로 spec/docs 수정 |
| Implementer | 계약 범위 내 구현 | 요구사항 재설계, 범위 확장 |
| Verifier | 검증·발견·반환 | **발견한 문제의 직접 수정** |
| Monitor | 감지·조사·분석·초안 (§6) | 모든 외부 write, Queue 확정, 세션 발급/중단 |

## 4. 파일 양식

### 4.1 `ASC.md` (한 번 쓰고 고정)

```markdown
# Agent Session Control — 로컬 전용, 커밋 금지

## 부트스트랩 (모든 세션 공통)
1. state.md 읽기 → 활성 세션 파일의 Handoff 읽기 → 내 세션 계약 확인
2. Canonical 목록의 정본을 실제로 다시 읽는다. 세션 파일의 요약을 믿지 않는다
3. 정본이 Handoff에 기록된 커밋 해시와 다르면 → 작업 전 diff 확인, 계약 영향 시 중단·보고

## 절대 규칙
- .asc/ 밖 쓰기는 세션 계약의 Write Boundary 안에서만
- 공식 영역(docs/specs/issue/PR/작업일지) 자동 반영 금지 — Controller 검토 후 사람 지시로만
- 미결 발견 → 임의 확정 금지, Handoff UNRESOLVED로 회수
- Verifier는 수정 금지 — 발견·반환만
- Red 접촉 → 즉시 중단, Handoff 작성 후 종료
- Work Session의 state.md 갱신 범위는 Execution 절 한정

## 작업일지 규칙과의 관계
- 공식 영역에 변경을 만든 세션(코드·문서·이슈 게시): 기존대로 그 세션이 작업일지 기록
- .asc/ 안에서만 끝난 세션(조사·계획·검증 리포트): 작업일지 기록 안 함. Handoff가 전부.
  공식 기록 여부는 Controller가 검토 후 결정

## Monitoring Plane
- Monitor는 감지·조사·상황 설명·영향 분석·대응 제안·답변 초안 작성까지 수행할 수 있다
- 모든 외부 커뮤니케이션은 초안 상태로 멈추고 Controller 승인을 기다린다
- Comment/Review/Issue/PR/Label/Assignee/Merge/Push 등 GitHub Write는 자동 수행하지 않는다
- AWAITING_APPROVAL 또는 APPROVED 상태만으로 게시 권한이 발생하지 않는다
- 실제 외부 반영은 Controller의 명시적 실행 지시가 있을 때만 수행한다
- 게시 실행은 Controller가 지시한 세션이 수행하고, 게시 직전 스레드 신규 이벤트를 재확인한다
- 패킷에는 정본 스냅샷(해시·이벤트 id)을 필수 기재한다
- 조사 깊이는 유형이 결정한다: 대응형·작업형 전체 패킷, 정보형 축약, P2 정보형 3줄
- log = 전 이벤트, inbox = 행동 가능 후보만. 전체 상태 재요약 금지
- inbox 상태 전이는 Controller 전용 (예외: [DONE]은 게시 지시받은 세션이 전환)
- Work Session ↔ Monitor 상호 불간섭. 중단 지시는 Controller만
- Controller: 새 S-* 발급 전 inbox 미처분 확인 권장
```

### 4.2 `state.md`

```markdown
# Execution                ← Work Session이 종료 시 갱신
활성 Block: B-05 (blocks/B-05.md)
활성 세션: 없음
최근 Handoff: sessions/S-20260821-02.md
병렬 세션 Write Boundary 점유: 없음
Controller 승인 대기: S-20260821-02 UNRESOLVED 2건

# Monitoring               ← 포인터만. 숫자·시각 넣지 않는다 (갱신자 없는 데이터 금지)
M-GITHUB: monitor/M-GITHUB.md (cursor·last_scan 그쪽 참조)
Inbox 미처분: monitor/inbox.md 확인

# Controller Attention     ← Controller만 기입
- (예) PR #44 Review — B-05 영향 가능
```

### 4.3 `controller.md` (사람만 수정)

```markdown
현재 목표: spec 005 FE 구현 완료
우선순위: docs/LJH/backlog.md 순서 (포인터 — 복사 안 함)
MVP 경계: specs/005-booth-studio-layout/spec.md 범위
승인 필요(Red): spec FR / 계약 / 미결 확정 / 게시·push·merge / PR·Issue 처리
표준 위임: 조사·분해·구현·검증. 판단 위임 안 함
Yellow 사전 허용: (세션별로 여기 추가 — 예: "vitest 의존성 추가 허용")
```

### 4.4 `blocks/B-NN.md`

```markdown
# B-05 Studio 편집기
목표: <Block 목표 1~2줄>
선행: <선행조건>
Gate(다음 Block 진입 조건): <검증 통과 기준> + Controller 승인
포함: <범위>
제외: <명시적 제외 — 이슈/spec 번호로>
Canonical:
- origin/develop:specs/005-booth-studio-layout/spec.md · contracts/ (공유 정본 — develop)
- specs/005-booth-studio-layout/FE/{plan,tasks}.md (FE 정본 — front 브랜치 로컬, develop 미병합)
- #19
세션 이력: S-20260821-02 (tasks 1~3 완료)
```

### 4.5 `sessions/S-YYYYMMDD-NN.md` — 계약 + Handoff 한 파일

```markdown
# S-20260822-01 — Role: Implementer
Block: B-05 / Tasks: specs/005-booth-studio-layout/FE/tasks.md T-004~T-006 (포인터)
Goal: <이 세션의 단일 목표>
Canonical(착수 시 재독 필수):
- origin/develop:specs/005-booth-studio-layout/spec.md @ <착수 시 해시 기입>
Read Scope: <읽기 허용 범위>
Write Boundary: <쓰기 허용 경로> + 이 파일
Out of Scope: <명시적 금지>
Authority: Green 자율 / Yellow 보고 / Red 중단
Escalation: Red 접촉·미결 발견 시 중단, Handoff로 회수
Done: <완료 조건> + Handoff 작성 + state.md(Execution 절) 갱신

---
## HANDOFF                 ← 세션 종료 시 작성
DONE:        실제 완료 항목
CHANGED:     변경 파일(미커밋/커밋해시). 계약 변경: 없음
VERIFIED:    방법 → 결과. self-check임을 명시 (Verifier 독립 검증 아님)
UNRESOLVED:  미결/위험 (등록 문안 준비됐으면 첨부 — 등록은 Controller)
NEXT:        바로 이어서 수행할 단 하나의 다음 작업
정본 스냅샷:  spec.md @ abc1234 기준으로 작업함
```

Verifier 세션: 같은 양식, Write Boundary = 자기 세션 파일뿐. FAIL 반환 형식은
`{항목, 기대, 실측, 근거 명령/파일:줄}` 을 Handoff VERIFIED 절에 기록.
수정은 Controller가 구현 세션 재발급으로.

### 4.6 `monitor/M-GITHUB.md`

```markdown
# M-GITHUB — Role: Monitor (감지+조사+초안)
대상 repo: kanghyunsoon/ssafesta
본인 식별: colosair (gh auth 계정과 일치 확인 후 scan)

## 감지 규칙 (트리거 → 기본 우선순위 제안)
P0 후보: assignee=colosair 신규 / @colosair mention / 본인 comment에 직접 회신
        / label:blocker 이면서 label:front / 활성 Block Canonical 문서를 바꾸는 PR 변화
P1 후보: review-request=colosair / 본인 PR의 새 Review·Review Comment
        / 본인 참여 Issue·PR의 상태 변화(close/reopen/merge)
P2 후보: label:front 신규·변경 (요청 없음 — 참고)
※ 실측 라벨: FE 관련은 `front` 하나. `frontend`/`FE` 없음. 타 파트: back/ai/game/infra

## 비후보 (log만, inbox 금지)
- 타 파트 라벨만 달린 활동 / 본인 무관 comment 일반 흐름 / CI·bot 이벤트

## Read Scope
- GitHub: Issue/PR/Review/Comment/Thread 전체 추적 (읽기)
- 프로젝트 정본: specs/, docs/, 관련 코드 (읽기)
- .asc: state.md, blocks/, sessions/ (Handoff 포함)

## Write: 이 파일 + inbox.md + log.md 만
## Forbidden: 코드·spec·docs 수정 / Comment 게시 / Review 제출 / Issue·PR 생성·수정·닫기
/ Label·Assignee 변경 / Merge / Push / queue.md·state.md 수정 / 공식 작업 생성
/ 우선순위 최종 확정 / Work Session 발급·중단 / 작성한 초안의 자동 게시

## Cursor
last_scan: <ISO8601>
notifications_last_modified: <Last-Modified 헤더 값>
issues_comments_since: <ISO8601>
seen_event_ids: log.md가 정본 — scan 시 tail 대조
```

### 4.7 `monitor/inbox.md` 패킷 양식

```markdown
## [AWAITING_APPROVAL] Issue #19 — <요약 제목>
Detected: <시각> / Source: <Issue/PR/Review/Comment 식별자>
Suggested Priority: P0

### 상황
무슨 일이 발생했고 상대방이 무엇을 요청/전달했는지.

### 관련 맥락
관련 기존 논의 / 현재 확정값 / Canonical 포인터 / Active Block·Session과의 관계

### 영향
현재 작업 중단 필요: Yes/No · 영향 범위 · 판단 근거

### 권장 대응
답변 / 확인 / 작업 필요 여부

### 답변 초안
GitHub에 바로 게시 가능한 완성 초안 (답변 불필요 이벤트는 생략)

### 정본 스냅샷
- specs/... @ <해시> / thread 마지막 event: <id>

### 처리 기록                ← Controller/게시 세션이 기입
- 결정: <승인/수정 승인/보류/불필요/Queue 승격> @ 시각
- 게시: <comment URL> (해당 시)
```

이벤트 유형별 산출물 차등:
- **정보형** (관련 PR merge, 상태 변화, Canonical 변경): 상황+영향 축약. 답변 초안 없음
- **대응형** (mention, 본인 comment 회신, review 요청): 전체 패킷 + 답변 초안
- **작업형** (신규 assign, 구현 요청, Block 영향 요구): 전체 패킷 + Suggested Priority.
  Queue 등록·세션 발급은 하지 않는다

### 4.8 `monitor/queue.md` (Controller 전용)

```markdown
READY
- PR #44 리뷰 영향 확인
ACTIVE
- B-05 validate 구현 → S-20260822-01
BLOCKED
- #32 FE 합의 → AI 답변 대기
DONE
- ...
```

### 4.9 `monitor/log.md` (append-only)

```text
2026-08-21 17:32 | Issue #19 | new comment | event-id: 31245xx | inbox: yes
2026-08-21 17:32 | PR #50    | CI run      | event-id: ...     | inbox: no (bot)
```

## 5. 운영 프로토콜 (Execution Plane)

**세션 시작** — 사람 프롬프트 한 줄:
`.asc/ASC.md와 state.md 읽고 S-20260822-01 계약대로 진행해라`
이전 세션 내용 복사·설명 불필요 — 그게 이 구조의 존재 이유.

**세션 종료** — Handoff 작성 + state.md Execution 절 갱신 + Block 이력 1줄.
여기까지가 에이전트 몫. 작업일지·이슈·PR 반영은 안 함 (공식 변경을 만든 세션 예외 — §4.1).

**Controller 검토 루프** — Handoff 읽음 → 승인/반려/미결 확정 → 공식 반영 필요분만
별도 지시("작업일지에 기록해" / "PR 올려"). 지시받은 시점부터 기존 흐름 그대로.

**병렬 세션** — Write Boundary 비겹침을 Controller가 계약 발급 시 보장.
state.md 점유 표가 충돌 감지선. 잠금 시스템 안 만든다 — 사람이 겹치게 발급 안 하면 된다.

**드리프트 가드** — Handoff "정본 스냅샷 @ 해시"와 부트스트랩 3단계의 대조가 핵심 장치.
구버전 정본으로 작업하는 사고를 기억이 아니라 절차로 방지.

## 6. 운영 프로토콜 (Monitoring Plane)

Monitor는 장기 상태(계약·cursor·로그)를 유지하는 **논리적** 세션이다. 물리적으로는
유한한 scan 1회씩 실행된다. Work Session과 상호 불간섭 — Monitor에게 Interrupt 권한 없음.
정본을 뒤집는 이벤트가 와도 inbox 기록까지만. 중단 판단은 Controller.

### 6.1 Scan 프로토콜 (2단)

```text
Phase A (경량, 전량):
1. ASC.md + M-GITHUB.md 읽기
2. gh api notifications (If-Modified-Since: cursor)   ← mention/assign/review_requested 1차 소스
3. gh api "repos/kanghyunsoon/ssafesta/issues?state=all&since=<cursor>&per_page=50"
   ← label:front 교집합 + 본인 참여 스레드 갱신
4. 열린 본인 PR 각각: gh pr view N --json reviews,comments,latestReviews
5. dedupe (notification thread_id+updated_at, comment/review id ↔ log.md tail 대조)
6. log.md append (전 이벤트) + 유형 분류(정보형/대응형/작업형) + P제안

Phase B (조사, inbox행 확정분만):
7. 관련 스레드·정본·.asc 상태 회수 → 패킷 작성(§4.7, 유형별 깊이 차등) → inbox 추가
8. cursor 갱신 ← Phase B 완료 후 (중간 실패 시 다음 scan이 재감지. 누락보다 중복이
   안전 — dedupe가 걸러줌)
```

전체 GitHub 상태를 매번 재요약하지 않는다. 신규 이벤트만 처리.

### 6.2 실행 트리거

1. **수동 (기본값)**: 별도 세션에서 `.asc/monitor/M-GITHUB.md 계약대로 scan 1회` 한 줄.
   scan은 read-only라 언제 돌려도 부작용 없음
2. `/loop` 세션 (20~30분 간격 자율 scan): 1이 자리 잡은 뒤 승격. 감지까지만 자동이므로 원칙 위배 아님
3. 스케줄드 태스크: 현 단계 불필요

### 6.3 Inbox lifecycle

```text
[AWAITING_APPROVAL]  Monitor 기입 (Monitor가 만드는 유일한 상태)
[QUEUED] [DEFERRED] [DISMISSED]  Controller 전용
[DONE]  게시·후속 처리를 지시받아 수행한 세션이 전환 (처리 기록 필수)
```

- 상태값 ≠ 게시 권한. 게시 트리거는 오직 Controller의 명시적 실행 지시
- 게시 지시받은 세션은 게시 직전 스레드 신규 이벤트 재확인 — 변화 있으면 중단·보고
- 처분 완료([DISMISSED]/[DONE]) 항목은 inbox에서 삭제 (감지 이력은 log, 게시물은 GitHub에 잔존).
  [DEFERRED]만 잔류
- 기각한 스레드의 **새 이벤트**는 다시 inbox에 들어옴 (의도된 동작 — 새 정보)

### 6.4 두 경로

```text
커뮤니케이션: Monitor 패킷 → Controller 승인 → 명시적 게시 지시 → 게시 → [DONE]
개발 작업:   Monitor 패킷 → Controller 승인 → queue.md READY → S-* 발급
```

inbox = 판단해야 할 것, queue = 수행하기로 승인한 것.

## 7. 알려진 트레이드오프 (수용함)

- **Interrupt 부재**: 정본을 뒤집는 Review가 와도 진행 중 세션은 모른 채 완주 → 낭비 가능.
  수용 — 세션은 유한하고 다음 세션의 스냅샷 대조가 잡는다. 낭비 한 세션이 Interrupt
  메커니즘보다 싸다. 완화: 긴 세션 발급 전 inbox 확인 습관 (ASC.md 명시)
- **state.md Monitoring 절 실시간성 없음**: 의도된 것. 갱신자 없는 숫자를 두지 않는다
- **inbox 단일 파일**: 패킷이 길어 비대해질 수 있음 → 처분 완료 삭제로 관리. 그래도 커지면 분리

## 8. 검증 이력

- GitHub 실측 (2026-08-21): 라벨 `front` 단일 확인, gh auth = colosair, notifications API
  사용 가능(unread 14건) — §4.6 필터값은 실측 기준
- spec 005 대입 검증: Block=B-05(Studio 편집기), Task 포인터=`FE/tasks.md` T-번호, Canonical=
  spec.md·contracts/는 develop, FE plan·tasks는 front 브랜치 로컬(develop 미병합 — §4.4 참조)
  — 기존 Spec→Block→Session→Task 흐름과 1:1 대응 확인. 새 개념 추가 없이 기존 단위에 계약
  필드만 입힘
- 독립 Verifier 검증 (2026-08-21): 내부 일관성 5항·사실 검증 4항·자기완결성 2항 중
  최초 9 PASS / 2 FAIL — FAIL 2건은 동일 근원(FE plan·tasks 경로를 develop 최상위로 오기재).
  §4.4·§4.5·§8 정정 완료. 교훈: 정본 포인터는 기재 전 `git ls-tree`로 실측한다

## 9. 도입 절차 (다음 세션이 여기서 시작)

1. `echo ".asc/" >> .git/info/exclude`
2. `.asc/` 생성 + `ASC.md`(§4.1)·`controller.md`(§4.3)·`state.md`(§4.2) 시드
3. `blocks/B-05.md` 작성 (§4.4 — backlog "Studio 편집기" 항목을 포인터로)
4. `monitor/` 생성: `M-GITHUB.md`(§4.6, cursor=현재 시각) + 빈 inbox/queue/log
5. 첫 scan 수동 실행 — 현재 unread가 초도 입력. inbox 후보 품질(잡음 비율) 보고 §4.6 필터 조정
6. 첫 실전 Work Session: 다음 spec 005 작업을 S-파일로 발급해 왕복 1회 검증

실패 시 `rm -rf .asc/` 로 원상복구 — 프로젝트 무영향.
