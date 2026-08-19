# specs/ — SDD 기능 명세

> **작성 기준일**: 2026-08-12 | **작성자**: Unity 리드 (초안)
> **spec-kit 버전**: 0.16.3 — `.specify/` 골격이 설치되어 있어 `/speckit-*` 명령이 바로 동작한다.

---

## 1. 지금 상태

**18개 spec 전부 초안이 있고, Unity 파트 6종(002·006·013·014·017·018)은 확정 완료다.**
나머지 12종은 팀 결정(2026-08-12)이 반영되어 있으며, 각 spec 하단의 **리뷰 3칸**을 담당 파트가 채우면 확정된다.

| Spec | 이름 | 우선순위 | 담당 | spec | plan | tasks |
|---|---|---|---|:---:|:---:|:---:|
| 001 | auth-user | P0 | BE + FE | ✅ | ✅ | ✅ |
| 002 | world-session | P0 | Unity + BE | ✅ **확정** | ✅ | ✅ |
| 003 | wallet-coin | P0 | BE | ✅ **확정** | ✅ +research/data-model/contracts/quickstart | ✅ |
| 004 | booth-slot-lease | P0 | BE + FE | ✅ | — | — |
| 005 | booth-studio-layout | P0 | FE + BE | ✅ | — | — |
| 006 | booth-runtime | P0 | Unity | ✅ **확정** | ✅ | ✅ |
| 007 | ai-agent-document | P0 | AI | ✅ | — | — |
| 008 | ai-conversation-rag | P0 | AI + FE | ✅ | — | — |
| 009 | project-exhibition | P0 | BE + FE | ✅ | — | — |
| 013 | avatar-customization | **P0** | Unity + FE + BE | ✅ **확정** | ✅ +research/data-model/contracts/quickstart | ✅ |
| 016 | booth-laptop-homepage | **P0** | FE + Unity + BE | ✅ | — | — |
| 010 | survey | P1 | FE + BE | ✅ | — | — |
| 011 | staff-consultation | P1 | BE + FE | ✅ | — | — |
| 012 | economy-inventory | P1 | BE | ✅ | — | — |
| 014 | minigame | P1 | Unity + BE | ✅ **확정** | ✅ | ✅ |
| 015 | dashboard | P1 | BE + FE | ✅ | — | — |
| 017 | proximity-voice | P1 | **FE + Infra** | ✅ **확정** | ✅ | ✅ |
| 018 | world-floors | P1 | Unity | ✅ **확정** | ✅ | ✅ |

**plan / tasks가 비어 있는 것은 각 파트가 직접 생성한다.** 그게 SDD의 정상 흐름이고,
`.specify/` 골격이 설치돼 있어서 명령만 실행하면 된다 (아래 §3).

## 2. 검토하는 법 (담당 파트가 먼저 할 일)

각 spec 맨 아래에 **리뷰 3칸**이 있다. 세 칸을 다 채워야 검토 완료다.

| 칸 | 해야 할 일 |
|---|---|
| ① Clarification 답변 | `C-xx` 표에 답한다. 모르면 비우지 말고 "언제까지 누가 정함"이라도 쓴다 |
| ② 틀린 요구사항 지적 | 리드가 추측으로 쓴 것을 고친다. **"좋아요"만 남기면 검토가 아니다** |
| ③ 빠진 요구사항 추가 | 담당자만 아는 필수 요구사항을 넣는다 |

> 초안 작성자가 clarify를 대신 답하지 않은 것은 의도다 — 헌법 30조.

## 3. spec-kit 사용법

**설치하지 마라. 저장소 안에 이미 들어 있고 전부 커밋돼 있다.** `git pull`이면 끝이다.
**Codex와 Claude Code 둘 다 설치 없이 바로 동작한다.**

```text
.specify/memory/constitution.md   ← 헌법 v1.2 (모든 명령이 참조)
.specify/templates/               ← spec / plan / tasks 템플릿
.specify/scripts/bash/            ← 명령이 호출하는 스크립트
.agents/skills/speckit-*/         ← Codex 용
.claude/skills/speckit-*/         ← Claude Code 용
specs/                            ← 이 폴더
```

> ❌ **`pip install specify-cli` / `specify init`을 실행하지 마라.**
> 재설치하면 위 `constitution.md`(우리 헌법 v1.2)가 **빈 템플릿으로 덮인다.**

**필요한 것**: Codex CLI 또는 Claude Code를 **저장소 루트에서** 실행 +
**bash**(Windows는 Git Bash / WSL — 스크립트가 `.sh`다).

**명령 실행 순서** — 이름은 같고 접두사만 다르다 (Codex `$`, Claude Code `/`):

| 명령 | Codex | Claude Code |
|---|---|---|
| specify (이미 있는 건 건너뛴다) | `$speckit-specify` | `/speckit-specify` |
| clarify — C-xx 답할 때 | `$speckit-clarify` | `/speckit-clarify` |
| **plan** — 각 파트가 여기서 시작 | `$speckit-plan` | `/speckit-plan` |
| tasks | `$speckit-tasks` | `/speckit-tasks` |
| analyze | `$speckit-analyze` | `/speckit-analyze` |
| implement | `$speckit-implement` | `/speckit-implement` |

결과물은 두 도구가 **같은 `specs/`**에 쌓는다. 팀원마다 다른 도구를 써도 된다.

> **스킬을 못 찾으면**: *"`.agents/skills/speckit-plan/SKILL.md`(Claude Code는 `.claude/skills/...`)를 읽고
> 그대로 실행해"* 라고 시키면 동일하게 동작한다. SKILL.md 자체가 완전한 절차서다.

**⚠️ 중요 — 작업할 spec을 먼저 지정한다.**

이 저장소의 spec들은 `/speckit-specify`가 아니라 손으로 만들었기 때문에, 명령이 "지금 어느 spec을 다루는지"를
모른다. 작업 전에 아래 둘 중 하나로 지정한다.

```bash
# 방법 A — 파일로 지정 (권장, 계속 유지됨)
echo '{ "feature_directory": "specs/004-booth-slot-lease" }' > .specify/feature.json

# 방법 B — 환경변수로 한 번만
export SPECIFY_FEATURE=specs/004-booth-slot-lease
```

지정이 되었는지 확인:

```bash
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only
# → {"REPO_ROOT":...,"FEATURE_DIR":...,"FEATURE_SPEC":...,"IMPL_PLAN":...,"TASKS":...} 가 나오면 정상
```

> 이 파일은 `.specify/.gitignore`에 의해 **커밋되지 않는다.** 사람마다 각자 만든다 — 정상이다.

**각 파트의 시작점**: 위처럼 자기 spec을 지정한 뒤 `$speckit-plan`(Claude Code는 `/speckit-plan`) 실행.
plan은 "어떤 기술로 어떻게"라서 그 파트만 제대로 쓸 수 있다 — 그래서 리드가 미리 쓰지 않았다.

## 4. 2026-08-12 확정 사항 (전 spec 반영 완료)

| 항목 | 확정값 |
|---|---|
| 로그인 | **Google + Kakao 소셜만.** 자체 가입 없음. 게스트는 둘러보기 전용(비영속) |
| 접속 토큰 검증 | **서명 자체 검증** + 사용 토큰 식별자 기록으로 재사용 차단 |
| Layout 좌표 | **미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z, 시계방향 +** |
| 부스 오브젝트 상한 | **12개** |
| 아바타 외형 | **ID 집합.** 네트워크는 고정 크기 struct, 저장은 TEXT (기존 "32자"는 무효) |
| 미니게임 | **타이머 정지 게임** (목표 5~10초 무작위, 단독 플레이) |
| 월드 세션 | **1차부터 목적 층 파라미터 포함** |
| 층 구조 | **층 = 별도 씬 + 별도 세션.** 엘리베이터가 전환을 가린다 |
| 임대 정책 | D01~D11 **권장안 전부 채택** (spec 004 상단 표 참조) |
| SSE 스키마 | `start / token / source / done / error` |
| Embedding | 1536차원 고정 |
| 1층 | **뼈대만** (부스 자리 + 포토존 위치). 상세 기획은 추후 |

## 5. 합동 확정이 남은 것

| 계약 | spec | 참여자 |
|---|---|---|
| Layout **왕복 검증 1회** (규칙은 확정, 실제 일치 확인 필요) | 005, 006 | FE + Unity |
| Unity → React 상호작용 payload | 006, 008, 016 | FE + Unity |
| SSE payload 상세 필드 | 008 | AI + FE |
| 층별 서버 인스턴스 구성 | 018 | Unity + Infra |

## 6. 주의 — 무효가 된 기존 문서

`festa-unity/Docs/avatar-customization-contract.md`는 **폐기**되었다 (이동 안내만 남아 있음).
아바타 계약은 `specs/013-avatar-customization/contracts/` 아래 3개 문서가 유일한 기준이다.

- 그 문서의 **"avatarCode 최대 29~32자"는 무효**다. 현재는 **ID 집합 + 저장 컬럼 TEXT** (헌법 23조).
- BE가 `VARCHAR(32)`로 만들면 T-24가 DB에서 재발한다.
