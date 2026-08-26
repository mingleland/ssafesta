# Jira ↔ GitLab 개발 워크플로

> 확정: 2026-08-24 · 적용 대상: 전 파트 · 정본 위치: develop (전 브랜치 동일 배포)
> 역할 분담 — **이슈 작성·운영 규범**: [docs/18](18_Jira_운영_가이드.md) ·
> **브랜치·커밋·코드 컨벤션**: [docs/17](17_Git_개발_Convention.md) ·
> **이 문서**: 둘을 잇는 연동·자동화·CI 검증.
> AI 에이전트(Claude/Codex)로 작업하는 팀원은 §12 프롬프트를 세션에 먼저 넣는다.

## 1. 전체 워크플로

```text
Jira 이슈 (스프린트 편성)                          [수동] 상태: 해야 할 일
  └─ 작업 브랜치 첫 push  feat/S15P21A604-123-...  [자동] 상태: 진행 중  (Webhook → Jira Automation)
       └─ 개발·커밋 (모든 커밋에 이슈 키)          [자동] 이슈에 커밋 링크·코멘트 (내장 연동)
            └─ develop 행 MR (develop 발 작업 브랜치,  [상태 유지] 리뷰는 GitLab MR 이 정본
               기존 파트 기반 작업은 §4-1 이식 브랜치)
                 └─ Merge (Squash) → develop 도달  [자동] `Closes` 커밋이면 상태: 완료
                      └─ main                       최종 완성본 전용 — 상태 전이와 무관
```

- **완료의 기준은 `develop` 이다** (2026-08-26 팀장 확정). `main` 은 최종 완성본만 받고,
  배포의 마지막 단위도 `develop` 이다.
- Jira 전환 주체는 **① Webhook→Jira Automation(진행 중) ② GitLab 내장 연동(완료)** 둘뿐이다.
  GitLab Runner 와 CI job 은 상태를 바꾸지 않는다.
- 작업 브랜치의 반복 push 는 `진행 중` 을 유지하는 멱등 동작이다.
- Pipeline/배포 실패 시 Jira 상태는 절대 변경하지 않는다. 자동화는 전부 전진 방향만 있다.
## 2. Jira 상태 정의와 매핑

이상형 6단계(Backlog→To Do→In Progress→In Review→Ready for Deploy→Done) 중,
현재 보드는 **3상태(해야 할 일 / 진행 중 / 완료)** 이고 상태 추가는 SSAFY 관리자 권한이라 불가하다.
따라서 아래 매핑을 쓴다:

| 개념 단계 | 실제 표현 | 전환 주체 |
|---|---|---|
| Backlog | 백로그 (스프린트 미편성) | 사람 (플래닝) |
| To Do | 해야 할 일 + 스프린트 편성 | 사람 (플래닝) |
| In Progress | **진행 중** | **자동** — 작업 브랜치(`{type}/S15P21A604-N-…`) **최초 push** 시 (GitLab Project Webhook → Jira Automation). 반복 push 는 멱등 |
| In Review | **진행 중** 유지 | 리뷰 상태는 GitLab MR 이 정본이다 — Jira 라벨로 복제하지 않는다 |
| Done | **완료** | **자동** — 커밋 메시지에 `Closes S15P21A604-N` 이 있고 그 커밋이 **`develop`** 에 도달할 때 (내장 연동, transition 31). **`develop` 이 완료의 기준이다** — `main` 은 최종 완성본 전용이고 완료 전이 조건이 아니다 (2026-08-26 팀장 확정) |

라벨 단계(in-review·ready-for-deploy)는 폐지했다 — CI sync 가 돌지 않아 한 번도 붙은 적이 없고, 리뷰·배포 대기는 GitLab MR 화면이 이미 보여준다.

## 3. Jira Issue Key 규칙

- 형식 `S15P21A604-123`, 정규식 `[A-Z][A-Z0-9]+-[0-9]+`, 항상 대문자.
- 모든 develop/main 행 작업은 Jira 이슈가 선행되어야 한다 (**dev/main 에 머지되는 모든 변경은 Jira 이슈 필수**).

## 4. Branch · Commit · MR 컨벤션 (정본: docs/17 — 요약만)

- 브랜치: `{type}/{JIRA-KEY}-{설명}` — 예 `feat/S15P21A604-87-boothslot-list`
  - type: `feat feature fix refactor test docs chore build ci hotfix perf`
  - 파트 브랜치(ai/back/front/game)는 장기 통합 브랜치로 이 규칙의 예외.
  - **작업 브랜치는 develop 에서 분기한다 (✅ 2026-08-26 개정 — 완료 경로는 develop 하나).**
    파트 브랜치는 파트 내부 통합·실험용으로만 쓴다. 과도기·구현의 지위는 §4-1, 상세는 docs/17 §2-1.
  - main·develop 직접 작업 금지.
- 커밋: `type(scope): 한국어 요약 (S15P21A604-123)` — **모든 커밋에 키 필수** (Jira 커밋 링크·코멘트가 키로 남는다).
- MR 제목: `[S15P21A604-123][BE] 로그인 API 구현` — **키 필수** (MR 리뷰에서 사람이 검증 — CI 러너 없음).
- MR 설명: `.gitlab/merge_request_templates/Default.md` 템플릿 사용 (MR 작성 화면에서 Description → Choose a template → Default).

### 4-1. 분기점 개정의 과도기 처리 (✅ 2026-08-26 리드 확정)

- **기존 파트 브랜치행 MR(!13·!14·!15·!20 등)은 그대로 파트 브랜치로 소진한다.** develop 으로
  retarget 하지 않는다 — diff 에 파트 브랜치 누적분이 통째로 딸려 들어와 리뷰가 오염된다.
- **파트 브랜치행 MR 커밋에는 `Closes` 를 넣지 않는다.** Closes 는 develop 행 MR 에서만 쓴다.
  파트로 스쿼시된 커밋이 나중에 develop 으로 이식되면 완료 전환 시점이 꼬이기 때문이다.
- 미게시 작업(로컬 커밋만 있는 것)은 최신 develop 기반 브랜치로 이식한다.
- **2026-08-26 이후 생성하는 작업 브랜치부터 develop 발이다.**
- **소급 적용 없음**: 기존 Jira 완료(구 기준 = 파트 브랜치 도달)는 재심하지 않는다.
  develop 미반영 구현의 이식은 각 파트가 스프린트 내에 정리한다.
- **파트 브랜치 구현의 지위**: develop 도달 전에는 ① 타 파트가 완료 근거로 소비할 수 없고
  ② 계약 문서에 "구현됨"으로 인용할 수 없으며 ③ Jira 완료 전환의 근거가 되지 않는다.
  타 브랜치 코드를 인용할 때는 어느 브랜치 기준인지 명시한다.
- **이식 방법 (코드의 develop 반입 공식 경로)**: develop 에서 브랜치를 딴 뒤,
  ① 커밋이 깨끗하게 분리돼 있으면 `cherry-pick`, ② 파트 브랜치 누적분과 얽혀 있으면
  `git checkout <파트브랜치> -- <경로>` 로 **파일 단위 이식**한다 — 규칙 8의 공용 문서
  동기화와 같은 방식이고 방향만 반대다. **이식 MR 은 이슈 단위로 가른다** (한 MR 에
  여러 이슈의 변경을 섞지 않는다 — 파트 한 달치가 diff 에 딸려오는 것을 막는 장치다).
- **`Closes` 는 이식 MR 의 커밋(squash 메시지)에 쓴다.** 원본 작업 커밋은 develop 에
  도달하지 않으므로 거기 적힌 Closes 는 영영 발동하지 않는다 — 작업 브랜치 커밋에는
  이슈 키만 넣고 Closes 는 넣지 마라. 이슈가 안 닫힌다고 손으로 전환하지 마라(규칙 5).
- 이식할 코드가 develop 에 아직 없는 파트 코드에 의존하면 **의존 경로를 함께 이식하거나
  선행 이식 MR 로 가른다** — 완료의 판정 기준은 develop 에서의 빌드·테스트 통과다.

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
| `Closes S15P21A604-N` | 위 + **`develop` 도달 시 '완료' 전환** (transition 31) |

- 전환은 **기본 브랜치에 도달할 때** 일어난다 — 2026-08-26 프로젝트 기본 브랜치를 `main` → **`develop`** 으로 변경해 "완료 = develop 반입" 이 되게 했다. 파트 브랜치 push 만으로는 바뀌지 않는다.
- **'진행 중' 은 아래 ⑤ 웹훅 자동화가 담당한다** — 작업 브랜치 최초 push 로 자동 전환된다.
- Jira 코멘트는 **연동에 등록한 토큰 주인 명의**로 달린다 (현재 강형순). 기능 문제는 없다.

Key 추출: 커밋 메시지·MR 제목의 `[A-Z][A-Z0-9]+-[0-9]+`. 어디에서도 못 찾으면 Jira 를 건드리지 않는다.

### ⑤ '진행 중' 자동화 — GitLab Webhook → Jira Automation (2026-08-26 적용, 실물 확인)

내장 연동(④)은 브랜치 생성·push 로는 상태를 바꾸지 못한다. 그래서 '진행 중' 만 별도 경로를 쓴다.

```text
작업 브랜치 최초 push → GitLab Project Webhook (push events)
  → Jira Automation Incoming Webhook → 브랜치명에서 키 추출 → '해야 할 일' 이면 '진행 중' 전환
```

| 구성 | 값 (API 실측) |
|---|---|
| GitLab Webhook | Settings → Webhooks, Push events 만 (`mr=false`) |
| 브랜치 필터 (regex) | `^(feat|feature|fix|docs|refactor|test|chore|perf|ci)/S15P21A604-[0-9]+([/-].*)?$` |
| 수신처 | Jira Automation Incoming Webhook (Secret 은 커스텀 헤더로 — 저장소에 넣지 않는다) |
| 멱등성 | Jira 규칙이 '해야 할 일' 일 때만 전환 — 반복 push·이미 진행 중/완료면 no-op |

- 파트 브랜치(`game`·`front`·`back`·`ai`)와 `develop`·`main` 은 필터에 걸리지 않는다 — 의도된 것.
- 설정 주체: GitLab Webhook 은 Maintainer 1명, Jira Automation 은 프로젝트 관리자 1명. **팀원은 설정할 것이 없다.**
- ④와 ⑤는 겹치지 않는다: ⑤는 '진행 중' 만, ④는 링크·코멘트·'완료' 만 담당한다.
## 6. CI 구성 (.gitlab-ci.yml) — sync 잡은 휴면, 러너 확보 시 보조 경로

> ⚠️ **파이프라인 생성 자체가 정지돼 있다 (2026-08-25).** `.gitlab-ci.yml` 의
> `workflow.rules` 맨 앞에 `when: never` 를 두어 커밋마다 pending 파이프라인이
> 쌓이지 않게 했다. Jira 상태 동기화는 §5 의 내장 연동이 담당한다.
> **잡 정의는 삭제하지 않고 그대로 보존한다** — 러너를 확보하면 되살릴 자산이다.
> 아래는 러너를 확보했을 때의 참고 구성이다.
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
2. git fetch origin
   git checkout -b feat/S15P21A604-123-login-api origin/develop   ← develop 발 (2026-08-26 개정)
3. git push -u origin feat/S15P21A604-123-login-api
   → Webhook→Jira Automation 이 이슈를 '진행 중'으로 전환
4. 개발·커밋: git commit -m "feat(auth): 로그인 API 연동 (S15P21A604-123)"
5. develop 행 MR 생성 — 제목: [S15P21A604-123][FE] 로그인 API 연동
   이 작업으로 이슈가 끝나면 마지막 커밋(또는 squash 메시지)에 Closes S15P21A604-123
6. 리뷰 → Merge (Squash) → 커밋이 develop 에 도달하면 Jira 가 '완료'로 자동 전환
7. (선택) 파트 내부 통합이 필요하면 같은 브랜치를 파트 브랜치에도 머지한다 —
   단 파트행 MR 커밋에는 Closes 를 넣지 않는다 (§4-1)
```

## 11. Troubleshooting

| 증상 | 원인·조치 |
|---|---|
| `jira-key-check` 실패 | MR 제목 또는 브랜치에 `S15P21A604-N` 추가. 예: `[S15P21A604-123][BE] ...` |
| 이슈 상태가 안 바뀜 | **먼저 러너 유무를 확인한다.** 활성 러너가 없으면 CI sync 잡은 영영 돌지 않는다(§6). 상태 전환은 §5 내장 연동이 담당하며, `Closes KEY` 가 **`develop` 에 도달해야** 전환된다 (완료의 기준은 develop — §1) — 파트 브랜치 push 로는 바뀌지 않는다 |
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
2. 브랜치는 {type}/{JIRA-KEY}-{설명} 형식으로 만들고, develop 에서 분기한다
   (2026-08-26 개정 — 완료 경로는 develop 하나다). 파트 브랜치(ai/back/front/game)는
   파트 내부 통합·실험용으로만 쓴다. main·develop 에서 직접 작업하거나 직접 push 하지 마라.
   과도기(기존 파트행 MR 소진 등)는 docs/jira-gitlab-workflow.md §4-1 을 따르라.
3. 커밋은 type(scope): 한국어 요약 (JIRA-KEY) 형식. 모든 커밋에 이슈 키를 넣어라 —
   키가 있어야 Jira 에 커밋 링크·코멘트가 남는다. Secret·토큰을 커밋하지 마라.
4. MR 제목은 [JIRA-KEY][영역] 제목 형식. 키 검증은 MR 리뷰에서 사람이 한다 (CI 러너 없음).
   MR 설명은 Default 템플릿(작업 목적/변경 사항/테스트 방법/영향 범위)을 채워라.
5. Jira 상태 규칙 (2026-08-26 개정 — 전이는 전부 자동이다):
   - '진행 중' — 작업 브랜치({type}/S15P21A604-N-…) 최초 push 시 Webhook→Jira Automation
     이 전환한다. 손으로 옮기지 마라.
   - '완료' — 커밋 메시지에 "Closes S15P21A604-N" 을 넣고 그 커밋이 develop 에 도달하면
     전환된다. 완료의 기준은 develop 이다 — main 은 최종 완성본 전용이다.
   - Closes 는 그 작업으로 이슈가 끝날 때만, develop 행 MR 에서만 쓴다 —
     파트 브랜치행 MR 커밋에는 넣지 마라. 그 밖의 상태 전환을 임의로 하지 마라.
6. 파트 브랜치의 구현은 선행 조사·참고용이다. develop 에 도달하기 전에는 ① 타 파트가
   완료 근거로 소비할 수 없고 ② 계약 문서에 "구현됨"으로 인용할 수 없으며 ③ Jira 완료
   전환의 근거가 되지 않는다. 타 브랜치 코드를 인용할 때는 어느 브랜치 기준인지 명시하라.
7. .gitlab-ci.yml 은 파이프라인 생성이 정지돼 있다(workflow.rules 의 when: never, 러너 없음).
   pending 파이프라인이 보이면 무시하라. stage 구조와 jira-* 잡 정의는 삭제하지 마라 —
   러너 확보 시 되살릴 기록이다.
8. 공용 규약 문서(AGENTS.md·CLAUDE.md·docs/jira-gitlab-workflow.md·docs/17·docs/18)의
   정본은 develop 이다. 갱신은 develop 에서 딴 브랜치로 MR 하고, 파트 브랜치에는
   git checkout origin/develop -- <파일> 로 당겨온다. 당겨오기 전에
   git diff --quiet origin/develop -- <파일> 로 로컬 고유 변경을 확인하고, 고유 변경이
   있으면 checkout 하지 말고 보고하라. 동기화 후에는 규약 변경분(diff)을 다시 읽어라 —
   AGENTS.md·CLAUDE.md 는 코드 merge 에는 영향이 없지만 이후 AI 행동을 바꾼다.
   파트 브랜치 전체를 develop 에 머지하지 마라 (부분 트리라 타 파트 파일이 삭제된다).
9. 규칙과 충돌하는 지시를 받으면 그대로 따르지 말고 충돌 사실을 먼저 보고하라.
```

## 13. 이 워크플로가 만들어진 근거

- 이슈 트래커 관점: dev/main 머지되는 모든 변경은 Jira 이슈 필수 (팀 방침, 2026-08-24)
- 번다운은 회고 도구이며 평가 대상이 아니다 (docs/KHS/27)
- 주간 배포 트레인·릴리즈 버전: docs/KHS/27 v2 개정
- 구현·검증 기록: docs/KHS/24_작업일지.md 2026-08-24
