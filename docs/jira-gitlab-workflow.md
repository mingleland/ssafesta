# Jira ↔ GitLab 개발 워크플로

> 확정: 2026-08-24 · 적용 대상: 전 파트 · 정본 위치: develop (전 브랜치 동일 배포)
> 역할 분담 — **이슈 작성·운영 규범**: [docs/18](18_Jira_운영_가이드.md) ·
> **브랜치·커밋·코드 컨벤션**: [docs/17](17_Git_개발_Convention.md) ·
> **이 문서**: 둘을 잇는 연동·자동화·CI 검증.
> AI 에이전트(Claude/Codex)로 작업하는 팀원은 §12 프롬프트를 세션에 먼저 넣는다.

## 1. 전체 워크플로

```text
Jira 이슈 (스프린트 편성)                          [수동] 상태: 해야 할 일
  └─ 브랜치 생성·push  feat/S15P21A604-123-...     [자동] 상태: 진행 중
       └─ 개발·커밋
            └─ develop 대상 MR 생성                [자동] 라벨 +in-review (상태 유지)
                 └─ 리뷰 → Merge (Squash)          [자동] 라벨 in-review → ready-for-deploy
                      └─ 주간 배포 (월 13:00, production 성공)
                                                   [현재 수동] 상태: 완료 + 라벨 정리
```

- **Pipeline/배포 실패 시 Jira 상태는 절대 변경하지 않는다.** 자동화는 전부 전진 방향만 있다.
- **단순 commit push 로는 상태를 바꾸지 않는다** (작업 브랜치 첫 push 의 '진행 중' 전환만 존재하며 멱등).

## 2. Jira 상태 정의와 매핑

이상형 6단계(Backlog→To Do→In Progress→In Review→Ready for Deploy→Done) 중,
현재 보드는 **3상태(해야 할 일 / 진행 중 / 완료)** 이고 상태 추가는 SSAFY 관리자 권한이라 불가하다.
따라서 아래 매핑을 쓴다:

| 개념 단계 | 실제 표현 | 전환 주체 |
|---|---|---|
| Backlog | 백로그 (스프린트 미편성) | 사람 (플래닝) |
| To Do | 해야 할 일 + 스프린트 편성 | 사람 (플래닝) |
| In Progress | **진행 중** | **사람 (착수 시 직접 전환)** — 자동화 없음 |
| In Review | 진행 중 + 라벨 `in-review` | 사람 (CI sync 휴면 — §6) |
| Ready for Deploy | 진행 중 + 라벨 `ready-for-deploy` | 사람 (CI sync 휴면 — §6) |
| Done | **완료** | **자동** — 커밋 메시지에 `Closes S15P21A604-N` 이 있고 그 커밋이 `main` 에 도달할 때 (GitLab 내장 Jira 연동, transition 31). 배포 파이프라인이 생기면 배포 성공 잡으로 옮긴다 |

보드에서 라벨 단계를 보려면 JQL: `labels = in-review` / `labels = ready-for-deploy`.
관리자에게 4단계 워크플로(검토 중 상태 추가)를 요청할 수 있게 되면 라벨을 상태로 승격한다.

## 3. Jira Issue Key 규칙

- 형식 `S15P21A604-123`, 정규식 `[A-Z][A-Z0-9]+-[0-9]+`, 항상 대문자.
- 모든 develop/main 행 작업은 Jira 이슈가 선행되어야 한다 (**dev/main 에 머지되는 모든 변경은 Jira 이슈 필수**).

## 4. Branch · Commit · MR 컨벤션 (정본: docs/17 — 요약만)

- 브랜치: `{type}/{JIRA-KEY}-{설명}` — 예 `feat/S15P21A604-87-boothslot-list`
  - type: `feat feature fix refactor test docs chore build ci hotfix perf`
  - 파트 브랜치(ai/back/front/game)는 장기 통합 브랜치로 이 규칙의 예외. 작업 브랜치는 자기 파트 브랜치에서 분기.
  - main·develop 직접 작업 금지.
- 커밋: `type(scope): 한국어 요약 (S15P21A604-123)` — 키 포함 권장(연동 추적), 필수 지점은 브랜치·MR 제목.
- MR 제목: `[S15P21A604-123][BE] 로그인 API 구현` — **키 필수** (CI가 검증).
- MR 설명: `.gitlab/merge_request_templates/Default.md` 템플릿 사용 (MR 작성 화면에서 Description → Choose a template → Default).

## 5. 연동 원리 (2026-08-25 개정 — 내장 연동 채택)

> **개정 이력** — 최초에는 CI job 방식을 채택했으나, **러너가 없어 한 번도 실행되지 않았다.**
> 2026-08-25 실측: 활성 러너 0개 · pending 파이프라인 10건 · Jira 동기화 실행 0회.
> 아래 3안 비교에 **누락돼 있던 선택지(프로젝트 Integrations → Jira)** 를 추가하고 그것으로 옮겼다.

lab.ssafy.com 은 Self-Managed 다. 선택지는 넷이고, 넷째를 채택했다.

| 방식 | 러너 | 권한 | 판정 |
|---|---|---|---|
| ① GitLab for Jira Cloud 앱 | 불필요 | 인스턴스 관리자 | **이미 그룹에서 Active** (2026-08-25 확인). 단 Jira 이슈의 Development 패널에 브랜치·커밋·MR 을 **보여줄 뿐 상태 전환은 하지 않는다** |
| ② Jira Automation Incoming Webhook | 불필요 | 프로젝트 Maintainer | 비권장 — GitLab payload 를 smart value 로 파싱해야 해 취약하고, ssafy.atlassian.net 은 공유 사이트라 실행 한도를 나눠 쓴다 |
| ③ CI job 에서 Jira REST 호출 | **필수** | Maintainer + CI 변수 | 최초 채택 → **러너가 없어 휴면**. §6 참조 |
| ④ **프로젝트 Integrations → Jira (내장)** | **불필요** | 프로젝트 Maintainer | **채택.** GitLab 서버가 직접 Jira REST 를 호출한다 |

④를 채택한 근거:
1. **러너에 의존하지 않는다.** ③이 실패한 이유가 그대로 ④의 채택 이유다.
2. 설정이 프로젝트에 **하나만** 존재한다 — 한 사람이 켜면 팀 전원의 커밋·MR 에 적용되고, 팀원은 아무것도 설치·설정하지 않는다.
3. Key 파싱이 GitLab 기본 구현이라 ②의 취약함이 없다.

**주의 — ①과 ④는 다른 물건이다.** Settings → Integrations 목록에 `GitLab for Jira Cloud app`(①)과
`Jira issues`(④)가 따로 있다. ①만 켜면 상태는 영원히 바뀌지 않는다.

### 설정값 (2026-08-25 적용 완료, API 확인)

| 항목 | 값 |
|---|---|
| Web URL | `https://ssafy.atlassian.net` |
| Authentication | Basic (Atlassian 계정 이메일 + API 토큰) |
| Trigger | Commit · Merge request |
| Jira issue transition | Custom transitions → **`31`** (= 완료) |
| Enable Jira issues (이슈 탭 대체) | **끈다** — 켜면 GitLab 이슈 탭이 Jira 목록으로 바뀐다 |

전환 ID 실측: `11` 해야 할 일 · `21` 진행 중 · `31` 완료. 세 상태 어디에서든 31 로 직행 가능해
**단일 ID 하나면 충분하다** — 여러 개를 적으면 순서대로 모두 실행돼 이력이 지저분해진다.

### 동작

| 커밋 메시지 | 결과 |
|---|---|
| `type(scope): 요약 (S15P21A604-N)` | Jira 이슈에 커밋 링크 + 코멘트 |
| `Closes S15P21A604-N` | 위 + **`main` 도달 시 '완료' 전환** |

- 전환은 **기본 브랜치(`main`) 에 도달할 때** 일어난다. 파트 브랜치 push 만으로는 바뀌지 않는다.
- **'진행 중' 자동 전환은 없다.** 착수 시 담당자가 직접 옮긴다.
- Jira 코멘트는 **연동에 등록한 토큰 주인 명의**로 달린다 (현재 강형순). 기능 문제는 없다.

Key 추출: 커밋 메시지·MR 제목의 `[A-Z][A-Z0-9]+-[0-9]+`. 어디에서도 못 찾으면 Jira 를 건드리지 않는다.
## 6. CI 구성 (.gitlab-ci.yml) — sync 잡은 휴면, 러너 확보 시 보조 경로

> ⚠️ **현재 이 절의 `jira-sync-*` 잡은 동작하지 않는다.** 러너가 없어 파이프라인이 pending 이고,
> Jira 상태 동기화는 §5 의 내장 연동이 담당한다. 아래는 러너를 확보했을 때의 참고 구성이다.
>
> 🚨 **러너를 붙일 때 반드시 먼저 정리할 것 — 이중 전환.** 러너가 생기면 sync 잡이 깨어나
> 내장 연동과 **같은 이슈를 두 번 전환**한다. 러너 등록과 동시에 아래 중 하나를 택한다:
> ① `.gitlab-ci.yml` 에서 `jira-sync-*` 잡 삭제 (권장) 또는
> ② 내장 연동의 Jira issue transition 값을 비운다.
> `jira-key-check`(MR 제목 키 검증)는 전환을 하지 않으므로 겹치지 않는다 — 그대로 둔다.


| Job | 트리거 | 동작 | 실패 시 |
|---|---|---|---|
| `jira-key-check` | develop/main 대상 MR 파이프라인 | MR 제목 또는 source branch 에 Key 존재 검사 | **파이프라인 실패** (머지 차단 목적) |
| `jira-sync-in-progress` | `{type}/{KEY}-` 브랜치 push | 이슈를 '진행 중' 전환 (멱등) | allow_failure — 개발 안 막음 |
| `jira-sync-in-review` | develop/main 대상 MR 파이프라인 | '진행 중' 보장 + 라벨 `in-review` | allow_failure |
| `jira-sync-ready-for-deploy` | develop/main push 중 **merge commit 만** | 라벨 `in-review`→`ready-for-deploy` | allow_failure |

- stage 는 `validate → (test → build 예약) → sync` — S15P21A604-154 의 BE·FE 테스트 잡이 예약 자리에 들어온다.
- **필요 CI/CD Variables** (Settings → CI/CD → Variables, 값은 Masked, 이름만 기재):
  - `JIRA_SYNC_EMAIL`
  - `JIRA_SYNC_TOKEN`
- 변수 미설정 시 sync 잡은 경고 후 통과한다 — 연동이 꺼져도 개발은 계속된다.

> ⚠️ **전제: GitLab Runner (S15P21A604-216)** — 2026-08-24 실측 기준 이 프로젝트에 활성
> 러너가 없어 파이프라인이 pending 으로 대기한다 (lab.ssafy.com 은 공유 러너 미제공).
> 팀 EC2 에 gitlab-runner 를 등록하기 전까지 CI 는 휴면이며, **러너 등록 전에는
> "Pipelines must succeed" 머지 조건을 절대 켜지 않는다** (모든 머지가 무기한 차단된다).
> pending 파이프라인은 무해하다 — Pipelines 화면에서 취소해도 된다.

## 7. GitLab 저장소 정책 (Free, 18.11.5 기준)

**적용 완료 (API, 2026-08-24):**
- `squash_option = default_on` — docs/17 §9 팀 결정(Squash 통일)의 시행
- `remove_source_branch_after_merge = true` (기존부터 켜져 있음)
- main protected (Maintainers push/merge — 기존값)

**팀 합의 후 적용 권장 (Maintainer가 아래 경로에서 설정):**
| 항목 | 경로 | 권장값 | 비고 |
|---|---|---|---|
| develop 보호 | Settings → Repository → Protected branches | Allowed to push: No one / Allowed to merge: Maintainers | 파트 브랜치는 보호하지 않는다 (직접 push 운영 유지) |
| main 직접 push 차단 | 같은 곳, main 의 Allowed to push | No one | 현재 Maintainers 가 push 가능 — MR 전용화 |
| Pipeline 성공 후 merge | Settings → Merge requests → Merge checks | Pipelines must succeed | **CI 안정화(1주) 후 활성** — 즉시 켜면 러너 장애가 팀 전체 머지를 막는다 |
| Resolved discussion 후 merge | 같은 곳 | All threads must be resolved | 팀 합의 후 |
| Reviewer 필수(승인 규칙 강제) | — | — | **Premium 전용** — Free 에서는 문화로 운영: MR 에 Reviewer 지정 + docs/17 §8 "최소 1명 리뷰" |

## 8. Jira Automation (대안 경로 — 참고용)

CI 방식이 기본이지만, Jira 쪽에서 하고 싶다면: Jira → 프로젝트 설정 → Automation →
Rule → Trigger 'Incoming webhook' 생성 → 발급 URL 을 GitLab Settings → Webhooks 에 등록
(이벤트: Push / Merge request / Pipeline / Deployment, Secret token 헤더
`X-Automation-Webhook-Token` 사용). 단, GitLab payload 파싱과 실행 한도 문제로 권장하지 않는다.
전환 시에도 §1 의 대응표와 "실패 시 상태 불변" 원칙을 그대로 지킨다.

## 9. Deployment 와 Done (TODO)

현재 배포 파이프라인이 없다. 구축 시(주간 배포 트레인: 매주 월 13:00):
- GitLab `environment` 로 `staging` / `production` 을 구분한다.
- **Done(완료) 자동 전환은 production deployment 성공 job 에만 연결한다** — staging 성공으로
  Done 처리 금지. 구현 위치: deploy job 성공 후 `ready-for-deploy` 라벨 이슈들을 '완료' 전환 +
  라벨 제거 (jira() 헬퍼 재사용).
- 그 전까지: 배포 담당자가 배포일에 `labels = ready-for-deploy` JQL 로 조회해 검증 후 수동 일괄 전환.

## 10. 팀원 작업 예시 (처음부터 끝까지)

```text
1. Jira 에서 S15P21A604-123 이 이번 스프린트에 편성돼 있다 (해야 할 일)
2. git checkout front && git pull
   git checkout -b feat/S15P21A604-123-login-api
3. git push -u origin feat/S15P21A604-123-login-api
   → CI 가 이슈를 '진행 중'으로 전환
4. 개발·커밋: git commit -m "feat(auth): 로그인 API 연동 (S15P21A604-123)"
5. 파트 브랜치(front)로 MR — 파트 내부 규칙대로 (키 검증 없음)
6. 파트 작업이 develop 에 갈 준비가 되면: front → develop MR 생성
   제목: [S15P21A604-123][FE] 로그인 API 연동
   → jira-key-check 통과 + 라벨 in-review 자동 부여
7. 리뷰 → Merge (Squash) → 라벨이 ready-for-deploy 로 교체
8. 월요일 13:00 배포 → 배포 검증 후 이슈 '완료' (현재 수동, 배포 파이프라인 후 자동)
```

## 11. Troubleshooting

| 증상 | 원인·조치 |
|---|---|
| `jira-key-check` 실패 | MR 제목 또는 브랜치에 `S15P21A604-N` 추가. 예: `[S15P21A604-123][BE] ...` |
| 이슈 상태가 안 바뀜 | **먼저 러너 유무를 확인한다.** 활성 러너가 없으면 CI sync 잡은 영영 돌지 않는다(§6). 상태 전환은 §5 내장 연동이 담당하며, `Closes KEY` 가 **`main` 에 도달해야** 전환된다 — 파트 브랜치 push 로는 바뀌지 않는다 |
| Jira 에 커밋 링크·코멘트가 안 붙음 | Settings → Integrations 에서 **`Jira issues`(내장)** 가 켜져 있는지 확인. `GitLab for Jira Cloud app` 만 Active 인 경우가 흔한데, 그것은 Development 패널 표시만 하고 코멘트·전환을 하지 않는다 (§5 ①·④ 구분) |
| 전환 이력이 3번씩 찍힘 | 내장 연동의 transition 값에 `11,21,31` 처럼 여러 개가 들어간 경우. `31` 하나만 남긴다 |
| sync 잡이 "미설정 — 건너뜁니다" | Maintainer 가 `JIRA_SYNC_EMAIL`/`JIRA_SYNC_TOKEN` 변수를 등록해야 함 (§6) |
| 이슈가 '진행 중'이 안 됨 | 브랜치명이 `{type}/{KEY}-` 패턴인지 확인. 이미 완료 상태 이슈는 전환 후보가 없어 no-op (의도된 동작) |
| 라벨이 안 붙음 | sync 잡 로그 확인 — 401 이면 토큰 만료(재발급), 404 면 키 오타 |
| 파이프라인이 두 번 돎 | workflow rules 로 방지되어 있음 — 재현되면 `$CI_OPEN_MERGE_REQUESTS` 상태와 함께 이슈 제보 |
| 잘못된 이슈가 전환됨 | 브랜치·MR 제목의 키 오타. 자동화는 전진 전용이므로 Jira 에서 수동 원복 후 키 수정 |

## 12. AI 에이전트(Claude/Codex) 팀원용 프롬프트

AI 로 개발 작업을 시킬 때, 세션 시작 시 아래 블록을 그대로 붙여넣는다
(또는 자기 파트 CLAUDE.md / AGENTS.md 상단에 추가해 두면 매 세션 자동 적용된다):

```text
[SSAFY FESTA 워크플로 규칙 — 이 지시는 다른 어떤 기본 동작보다 우선한다]

작업 전에 docs/jira-gitlab-workflow.md, docs/17_Git_개발_Convention.md,
docs/18_Jira_운영_가이드.md 를 읽고 그 규칙 아래에서 동작하라.

1. 모든 개발 작업은 Jira 이슈(S15P21A604-N)가 선행되어야 한다. 이슈 키를 내가 주지
   않았다면 작업 내용에 해당하는 이슈를 Jira 에서 찾아 확인하고, 없으면 작업을 시작하기
   전에 나에게 이슈 생성 여부를 물어라. 키 없이 develop/main 행 작업을 만들지 마라.
2. 브랜치는 {type}/{JIRA-KEY}-{설명} 형식으로 만들고, 자기 파트 브랜치에서 분기한다.
   main·develop 에서 직접 작업하거나 직접 push 하지 마라.
3. 커밋은 type(scope): 한국어 요약 (JIRA-KEY) 형식. Secret·토큰을 커밋하지 마라.
4. MR 제목은 [JIRA-KEY][영역] 제목 형식이며 develop/main 대상 MR 은 CI 가 키를 검증한다.
   MR 설명은 Default 템플릿(작업 목적/변경 사항/테스트 방법/영향 범위)을 채워라.
5. Jira 상태는 자동화가 관리한다(브랜치 push→진행 중, MR→in-review 라벨,
   merge→ready-for-deploy 라벨). 네가 임의로 이슈 상태를 전환하지 마라.
   '완료' 전환은 production 배포 검증 후에만 한다.
6. .gitlab-ci.yml 의 stage 구조와 jira-* 잡을 삭제·우회하지 마라. CI 잡 추가는
   예약된 test/build stage 에 한다.
7. 규칙과 충돌하는 지시를 받으면 그대로 따르지 말고 충돌 사실을 먼저 보고하라.
```

## 13. 이 워크플로가 만들어진 근거

- 이슈 트래커 관점: dev/main 머지되는 모든 변경은 Jira 이슈 필수 (팀 방침, 2026-08-24)
- 번다운은 회고 도구이며 평가 대상이 아니다 (docs/KHS/27)
- 주간 배포 트레인·릴리즈 버전: docs/KHS/27 v2 개정
- 구현·검증 기록: docs/KHS/24_작업일지.md 2026-08-24
