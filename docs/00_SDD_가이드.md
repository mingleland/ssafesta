# SSAFY FESTA — SDD 작업 가이드

> **이 문서가 SDD의 출발점이다.** 처음이면 §1부터, 이미 알면 §3(내 파트)부터 보면 된다.
> 작성: 2026-08-12 (Unity 리드) | 갱신: 2026-08-13
> **Codex와 Claude Code 둘 다 쓸 수 있다.** 규칙은 같고 명령 접두사만 다르다 (`$` vs `/`).
> AI 에이전트가 지켜야 할 규칙 전문은 루트 **`AGENTS.md`**다. 이 문서는 사람이 읽는 입문서다.

---

## 1. 5분 요약 — 지금 뭘 해야 하나

```text
[0] 세팅                →  §1-1  ★ spec-kit은 깔지 않는다. clone하면 끝이다
[1] 내 spec 찾기        →  §3 표에서 내 이름/파트 칸
[2] 내 spec 점검하기     →  §4 리뷰 3칸을 채운다  ← 지금 여기
[3] plan/tasks 만들기   →  §5 speckit-plan 실행  (Codex `$` / Claude Code `/`)
[4] 구현 + 기록          →  §6 작업 사이클
```

**지금 할 일은 [2]다.** spec 18종의 초안이 이미 있고, 각 파트가 자기 것을 검토해야 확정된다.
*(Unity 파트 6종은 리드가 이미 확정 — §3 참조)*

### 1-1. 세팅 — spec-kit은 설치하지 않는다 ★

**"나는 spec-kit이 없는데?" → 깔 필요 없다. 저장소 안에 들어 있고 전부 커밋돼 있다.**

| 들어 있는 것 | 위치 |
|---|---|
| 헌법 · 템플릿 · 스크립트 | `.specify/` |
| **Codex용** speckit 명령 10종 | `.agents/skills/speckit-*/SKILL.md` |
| **Claude Code용** speckit 명령 10종 | `.claude/skills/speckit-*/SKILL.md` |
| 기능 명세 18종 | `specs/` |

**Codex를 쓰든 Claude Code를 쓰든 둘 다 설치 없이 바로 동작한다.**
`git pull` → **저장소 루트에서** 도구 실행 → 끝이다.

> ❌ **`pip install specify-cli` / `specify init`을 실행하지 마라.**
> 재설치하면 `.specify/memory/constitution.md`(우리 헌법 v1.3)가 **빈 템플릿으로 덮인다.**

필요한 것은 두 가지뿐이다.

| 항목 | 비고 |
|---|---|
| **Codex CLI 또는 Claude Code** | 둘 중 아무거나. **반드시 저장소 루트에서** 실행 — 하위 폴더면 `.specify/`를 못 찾는다 |
| **bash** | 스크립트가 `.sh`라서 **Windows는 Git Bash 또는 WSL**에서 돌린다 (두 도구 공통) |

AI 에이전트에게 시킬 규칙 전문은 루트 **`AGENTS.md`**에 있다.
Claude Code는 `CLAUDE.md`를 먼저 읽지만, 거기에도 **"`AGENTS.md`를 전부 읽어라"**라고 적어뒀다.

확인 한 줄 — `FEATURE_DIR` 등이 나오면 정상이다.

```bash
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only
```

---

## 2. 어디에 뭐가 있나

```text
SSAFESTA/
├── .specify/
│   ├── memory/constitution.md      ★ 프로젝트 헌법 v1.3 — 모든 결정의 최상위 근거
│   ├── templates/                  spec/plan/tasks 템플릿
│   ├── scripts/bash/               명령이 호출하는 스크립트
│   └── feature.json                ★ "지금 작업 중인 spec" 지정 파일
├── .claude/skills/speckit-*/       Claude Code 용 명령
├── .agents/skills/speckit-*/       Codex 용 명령
├── specs/
│   ├── README.md                   spec 목록 + 확정 사항 요약
│   └── NNN-이름/
│       ├── spec.md                 무엇을 만들 것인가 — **공동 정본** (파트별 섹션 접붙임)
│       ├── contracts/              파트 경계를 넘는 계약만 (REST·Layout JSON·Bridge 이벤트)
│       └── {BE,FE,Unity,AI}/       파트별 실행 산출물 — plan·research·data-model·quickstart·tasks
│                                   (#43 채택, 2026-08-21. 단일 파트 spec 은 루트에 그대로 둬도 된다)
└── docs/
    ├── 00_SDD_가이드.md             ← 이 문서
    ├── 24_작업일지.md 25_트러블슈팅.md   기록 규칙의 본보기
    ├── 26_팀_결정_필요사항.md        결정 로그 (미정 항목은 여기에 올린다)
    └── sdd/parts/{BE,FE,AI,INFRA}.md  파트별 참고 브리프
```

**다중 파트 spec 산출물 규칙 (#43, 2026-08-21 채택)** — 한 spec 을 여러 파트가 담당하면
`plan.md` 등 실행 산출물 5종은 **파트별 디렉터리**(`BE/`·`FE/`·`Unity/`·`AI/`)에 만든다.
파일명이 하나뿐이라 먼저 쓴 파트가 자리를 차지하는 문제(001·004·005·013 에서 실제 발생)의 해결책이다.

- `spec.md` 와 최상위 `contracts/` 만 공동 정본이다. 파트 내부 계약은 자기 디렉터리의 `contracts/` 에 둔다.
- spec-kit 은 `.specify/feature.json` 에 **자기 파트 디렉터리까지** 지정한다 —
  예: `{ "feature_directory": "specs/005-booth-studio-layout/FE" }`. 이 파일은 gitignore 대상이라 충돌이 없다.
- ⚠️ 이때 `FEATURE_SPEC` 은 `FE/spec.md` 를 가리키지만 **정본은 상위 디렉터리의 `spec.md` 다.**
  스텁을 만들지 말고(정본 2벌 = drift) 명세는 상위에서 읽는다.
- 단일 파트 spec(003·006·007 등)은 루트 산출물을 유지한다. 두 번째 파트가 산출물을 만들기
  시작하는 시점에 기존 것을 담당 파트가 자기 디렉터리로 `git mv` 한다.
- 한 파일 안에서도 같다 (#59, 2026-08-23 채택) — C-xx 상태(`대기/보류/확정`)의 정본은
  **Clarifications(미결) 표 1곳**이다. 검증표·리뷰 섹션·안내문 등 다른 섹션은 상태를 복제
  서술하지 않고 **확정된 동작·정책만** 기술한다. (근거: spec 005 #45 / spec 007 #51 —
  상태 복제가 확정 번복 시 문서 자기모순 유발)

**Claude를 쓰든 Codex를 쓰든 같은 `specs/`와 `.specify/`를 본다.** 누가 작업해도 결과가 한 곳에 쌓인다.

---

## 3. 내 파트의 SDD 파일

### Backend (Spring)

| Spec | 이름 | 역할 | 우선 |
|---|---|---|---|
| **001** | auth-user | 주담당 — 소셜 로그인·토큰 4계층 | ★ 1순위 |
| **003** | wallet-coin | 주담당 — 지갑·원장·멱등성 | ★ 2순위 |
| **004** | booth-slot-lease | 주담당 — 임대·동시성 | ★ 3순위 |
| 002 | world-session | world-sessions API + 토큰 서명 검증 (Unity는 완료) | |
| 005 | booth-studio-layout | Layout 저장·공개 API | |
| 009 | project-exhibition | CRUD | |
| 013 | avatar-customization | ⚠️ 아바타 저장 컬럼 — **TEXT로 만들 것** | |
| 016 | booth-laptop-homepage | 홈페이지 URL 저장·검증 | |
| 010·011·012·015 | 설문·상담·경제·대시보드 | P1 | |

참고 브리프: `docs/sdd/parts/BE.md`

### Frontend — 이정헌 (Platform & Booth)

| Spec | 이름 | 역할 |
|---|---|---|
| **005** | booth-studio-layout | 주담당 — **Layout 계약의 주인** |
| **016** | booth-laptop-homepage | 주담당 — ⚠️ iframe 차단 대응이 핵심 |
| 001 | auth-user | 로그인 UI + FE 공통 구조 |
| 013 | avatar-customization | 커스터마이징 창 이관 (Unity Lobby 완성 후) |
| 009·010 | 프로젝트·설문 | |

**spec보다 먼저 할 일**: FE 공통 구조와 Overlay Platform 계약 확정. 김가현이 여기에 얹는다.
참고 브리프: `docs/sdd/parts/FE.md`

### AI / Conversation — 김가현

| Spec | 이름 | 역할 |
|---|---|---|
| **007** | ai-agent-document | 주담당 — 문서 파이프라인 |
| **008** | ai-conversation-rag | 주담당 — ⚠️ **격리 테스트가 릴리스 차단 조건** |
| 011 | staff-consultation | 상담 UI + Handoff Summary (P1) |

참고 브리프: `docs/sdd/parts/AI.md`

### Infra

| 대상 | 내용 |
|---|---|
| **CI/CD** | 파트 브랜치 4개 개별 배포 + develop 실사용 기준 (헌법 10조) |
| **AWS wss 실측** | 헌법 6조의 마지막 빈칸(ALB/NLB)을 채운다 |
| 017 | proximity-voice — SFU 호스팅 (P1) |
| 018 | world-floors — 층별 서버 인스턴스 (P1) |

참고 브리프: `docs/sdd/parts/INFRA.md`

### Unity — 리드

| Spec | 상태 |
|---|---|
| 002·006·014·017·018 | ✅ **확정** — spec + plan + tasks 보유 |
| **013** | ✅ **확정** — spec + plan + research + data-model + quickstart + contracts 3종 |
| | 구현 지시: `docs/29_아바타_커스터마이징_작업지시.md` |
| | ⚠️ **용량 주의** — 신규 에셋 소스 약 141MB vs 현재 빌드 약 87MB. 목표는 87MB 유지 |

**Unity 파트는 검토가 끝났다.** 다른 파트는 아래 §4를 먼저 하고 §5로 간다.

---

## 4. 점검 — 무엇을 기준으로 보나

각 `spec.md` 맨 아래에 **리뷰 3칸**이 있다. **세 칸을 다 채워야 검토 완료**다.

| 칸 | 기준 | 하면 안 되는 것 |
|---|---|---|
| ① Clarification 답변 | `C-xx` 표의 질문에 답한다. 모르면 "언제까지 누가 정함"이라도 쓴다 | 비워두기 |
| ② 틀린 요구사항 지적 | 리드가 추측으로 쓴 것 중 사실과 다르거나 과한 것을 고친다 | **"좋아요"만 쓰기 — 그건 검토가 아니다** |
| ③ 빠진 요구사항 추가 | 담당자만 아는 필수 요구사항을 넣는다 | 나중에 구현하며 몰래 추가하기 |

### 점검할 때 던질 질문 5개

1. **이대로 만들면 내가 아는 그 기능이 되나?** — 아니면 ②에 적는다
2. **여기 없는데 반드시 필요한 게 있나?** — ③에 적는다
3. **C-xx 중 내가 지금 답할 수 있는 게 있나?** — 있으면 지금 답한다. 회의를 기다리지 않는다
4. **헌법과 충돌하는 게 있나?** — 있으면 spec이 아니라 헌법 개정 안건이다
5. **다른 파트와 맞춰야 하는 게 있나?** — §7 계약 표에 있는지 확인

### 절대 하면 안 되는 것

> **미정 항목을 구현자가 임의로 확정하지 않는다** (헌법 30조).
> 예: "임대 1일이 뭔지 안 정해졌으니 일단 24시간으로 하자" → **금지**.
> `docs/26_팀_결정_필요사항.md`에 올리고 팀 결정으로 푼다.

---

## 5. plan / tasks 만들기

spec을 점검·확정했으면 그 다음은 명령이 대신 해준다.

### 5-1. 작업할 spec을 먼저 지정한다 ⚠️

spec을 손으로 만들었기 때문에 명령이 "지금 어느 spec인지" 모른다. **매번 바꿔가며 지정한다.**

```bash
echo '{ "feature_directory": "specs/003-wallet-coin" }' > .specify/feature.json
```

확인:

```bash
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only
# FEATURE_DIR / FEATURE_SPEC / IMPL_PLAN / TASKS 가 나오면 정상
```

### 5-2. 명령 실행 — 이름은 같고 접두사만 다르다

| 명령 | Codex | Claude Code | 언제 |
|---|---|---|---|
| clarify | `$speckit-clarify` | `/speckit-clarify` | 모호한 부분을 좁힌다 ← C-xx 답할 때 |
| **plan** | `$speckit-plan` | `/speckit-plan` | 기술 계획 수립 ← **여기서 시작** |
| tasks | `$speckit-tasks` | `/speckit-tasks` | 작업 목록 생성 |
| analyze | `$speckit-analyze` | `/speckit-analyze` | spec·plan·tasks 일관성 점검 |
| implement | `$speckit-implement` | `/speckit-implement` | 구현 |

**결과물은 두 도구가 같은 `specs/`에 쌓는다** — 누가 무엇으로 작업해도 한 곳에 모인다.
팀원마다 다른 도구를 써도 상관없다.

> **도구가 스킬을 못 찾으면** 이렇게 시키면 똑같이 동작한다.
> SKILL.md 자체가 완전한 절차서라 별도 설치가 필요 없다.
>
> - Codex — *"`.agents/skills/speckit-plan/SKILL.md`를 읽고 그대로 실행해"*
> - Claude Code — *"`.claude/skills/speckit-plan/SKILL.md`를 읽고 그대로 실행해"*

**왜 plan을 리드가 안 써줬나**: plan은 "어떤 기술로 어떻게"라서 그 파트만 정확하게 쓸 수 있다.
남이 추측으로 써준 plan은 오너십도 정확도도 잃는다.

---

## 6. 작업 사이클 — 앞으로 어떻게 고쳐가며 일하나

```text
   spec 점검·확정
        ↓
   /speckit-plan → /speckit-tasks
        ↓
   구현 (tasks 하나씩)
        ↓
   ┌─ 요구사항이 틀렸다는 걸 발견? ──→ spec.md 수정 → plan/tasks 재생성
   ├─ 기술 선택이 틀렸다? ──────────→ plan.md만 수정
   ├─ 작업 순서가 틀렸다? ──────────→ tasks.md만 수정
   └─ 계약(파트 간)이 바뀌어야 한다? ─→ §7 절차
        ↓
   작업일지 + 트러블슈팅 + Jira 기록
```

### 어디를 고쳐야 하나

| 상황 | 고칠 파일 |
|---|---|
| "이 기능은 사실 이렇게 동작해야 한다" | **spec.md** (요구사항) → plan/tasks 재생성 |
| "이 라이브러리 말고 저걸 쓰자" | **plan.md** (기술 선택) |
| "이 작업을 먼저 해야 한다" | **tasks.md** (순서·분할) |
| "이건 프로젝트 원칙에 어긋난다" | **헌법** — 팀 합의 필요 |
| "다른 파트와 주고받는 형식이 바뀐다" | **§7 계약 절차** |

### 규칙 3가지

1. **spec을 고치면 plan/tasks를 다시 만든다.** 요구사항이 바뀌었는데 계획이 그대로면 어긋난다.
2. **구현하며 몰래 요구사항을 늘리지 않는다.** 필요하면 spec에 먼저 적는다.
3. **이미 동작하는 기준선 코드를 재작성하지 않는다** (헌법 27조). 002·006은 소급 spec이라
   `[구현됨]` 표시가 있다. 그건 다시 만들라는 뜻이 아니다.
   *(013은 예외 — 에셋 교체로 재구현하되 `NetworkPlayer`·Connection·Booth는 그대로 둔다.)*

---

## 7. 파트 간 계약 — 혼자 바꾸면 안 되는 것

아래는 **여러 파트가 함께 소비**한다. 한 명이 바꾸면 다른 파트가 조용히 깨진다.

| 계약 | 관련 spec | 합의 대상 |
|---|---|---|
| **Layout JSON** (좌표·필드) | 005·006·016 | FE + BE + Unity |
| Unity → React 상호작용 payload | 006·008·016 | FE + Unity |
| SSE 이벤트 payload | 008 | AI + FE |
| 아바타 외형 데이터(ID·저장 형식) | 013 | Unity + BE |
| world-sessions 응답·층 파라미터 | 002·018 | BE + Unity |

**변경 절차** (헌법 24조): 영향 파트 확인 → 문서 수정 → DTO 변경 → Consumer 수정 → 통합 테스트 → MR에 Breaking Change 표시.

> ⚠️ **Layout 좌표는 규칙이 확정됐지만 아직 검증되지 않았다.**
> React가 오브젝트 1개짜리 Layout을 보내고 Unity에서 같은 위치인지 **눈으로 확인하는 왕복 1회**가 남아 있다.
> 부호 하나는 반드시 틀린다는 전제로 하는 게 안전하다.

---

## 8. 기록 의무 (헌법 29조)

| 언제 | 무엇을 |
|---|---|
| 작업이 끝날 때마다 | **본인 작업일지**에 날짜별로 기록 |
| 트러블이 생길 때마다 | **본인 트러블슈팅 문서**에 날짜 포함해 기록 (해결 못 했어도) |
| 매일 작업 종료 시 | **Jira 1회 갱신** (필수) |

형식은 `docs/24_작업일지.md` / `docs/25_트러블슈팅.md`를 그대로 따라하면 된다.
AI 세션(Claude/Codex)에 시킨 작업도 동일하게 기록한다.

---

## 9. 자주 하는 실수

| 실수 | 왜 문제인가 |
|---|---|
| `.specify/feature.json` 지정 안 하고 명령 실행 | 명령이 어느 spec인지 몰라 실패한다 |
| **spec-kit을 새로 설치**(`specify init`) | **헌법 v1.3가 빈 템플릿으로 덮인다.** 이미 저장소에 있다 (§1-1) |
| 저장소 루트가 아닌 하위 폴더에서 실행 | `.specify/`를 못 찾아 명령이 실패한다 |
| 리뷰 ②칸에 "좋아요"만 쓰기 | 검토가 아니다. 통합 시점에 터진다 |
| 미정 항목을 혼자 정하고 구현 | 나중에 뒤집힐 때 비용이 크다 (헌법 30조) |
| 계약을 혼자 바꾸고 알리지 않기 | 다른 파트가 조용히 깨진다 |
| spec 안 고치고 구현만 바꾸기 | 문서와 코드가 갈라진다 |
| 아바타 컬럼을 `VARCHAR(32)`로 만들기 | 구 계약이며 무효다. **TEXT로** (헌법 23조) |
| 이미 되는 기능을 spec 보고 다시 만들기 | 002·006은 소급 spec이다 (헌법 27조) |

---

## 10. 2026-08-12 확정 사항 (전 spec 반영 완료)

| 항목 | 확정값 |
|---|---|
| 로그인 | Google + Kakao **소셜만**. 자체 가입 없음. 게스트는 둘러보기 전용(비영속) |
| 접속 토큰 | **서명 자체 검증** + 사용 토큰 식별자 기록 |
| Layout 좌표 | **미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0 = +Z** |
| 부스 오브젝트 | **최대 12개** |
| 아바타 외형 | **ID 집합.** 네트워크는 고정 크기 struct, 저장은 TEXT |
| 미니게임 | **타이머 정지 게임** (목표 5~10초 무작위, 단독) |
| 월드 세션 | **1차부터 층 파라미터 포함** |
| 층 구조 | **층 = 별도 씬 + 별도 세션** (2차) |
| 임대 정책 | D01~D11 **권장안 전부 채택** |
| SSE | `start / token / source / done / error` |
| Embedding | 1536차원 고정 |
| 1층 | **뼈대만** (부스 자리 + 포토존 위치) |

전체 목록: `specs/README.md` §4 / 근거: `.specify/memory/constitution.md`

---

## 11. 막히면

| 상황 | 어디로 |
|---|---|
| 결정이 필요해서 막힘 | `docs/26_팀_결정_필요사항.md`에 등록 + 리드에게 알림 |
| 기술 문제로 막힘 | 본인 트러블슈팅에 먼저 기록하고 공유 (같은 문제를 둘이 겪지 않게) |
| spec이 틀린 것 같음 | 리뷰 ②에 적는다. **그게 정상이고 그러라고 만든 칸이다** |
| 명령이 안 돌아감 | §1-1 (루트에서 실행? bash 있나?) → §5-1 `feature.json` 지정 확인 |
| Codex / Claude Code가 speckit 스킬을 못 찾음 | §5-2 아래의 "SKILL.md를 읽고 실행" 방식으로 우회 |
| AI 에이전트 규칙 전문이 필요함 | 루트 **`AGENTS.md`** |
