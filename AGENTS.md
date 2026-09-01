# SSAFY FESTA — AI 에이전트 운영 매뉴얼

> **이 파일이 AI 에이전트 규칙의 단일 출처다. Codex든 Claude Code든 똑같이 적용된다.**
> 두 도구의 차이는 **명령 접두사 하나뿐**이고(`$` vs `/`), 결과물은 같은 `specs/`에 쌓인다.
> **이 파일 하나로 작업을 시작할 수 있게** 쓴다. 규칙이 바뀌면 다른 문서보다 **여기를 먼저** 고친다.
>
> 최종 갱신: 2026-08-13 | 대상: **Codex · Claude Code** / 기타 AI 에이전트

| 도구 | 자동으로 읽는 파일 | speckit 명령 | 명령 정의 위치 |
|---|---|---|---|
| **Codex** | `AGENTS.md` (이 파일) | `$speckit-plan` | `.agents/skills/speckit-*/SKILL.md` |
| **Claude Code** | `CLAUDE.md` → 이 파일을 가리킨다 | `/speckit-plan` | `.claude/skills/speckit-*/SKILL.md` |

> **Claude Code로 작업한다면**: `CLAUDE.md`만 읽고 시작하지 마라. 거기엔 요약만 있다.
> **이 파일 전문을 읽고** 그대로 따른다. 규칙은 두 도구가 완전히 동일하다.

---

## 필수: Jira ↔ GitLab 워크플로 규칙 (docs/jira-gitlab-workflow.md §12)

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

## 0. 세션을 시작하면 이 순서로 한다

```text
[1] 이 파일 전체를 읽는다                          ← Codex / Claude Code 공통
[2] .specify/memory/constitution.md (헌법 v1.3)  ← 모든 결정의 최상위 근거
[3] 작업할 spec 지정:  .specify/feature.json      ← §2-3. 안 하면 명령이 실패한다
[4] specs/NNN-*/spec.md + plan.md + tasks.md      ← 목록은 specs/README.md
[5] docs/25_트러블슈팅.md 의 T-24 ~ T-27          ← 최근에 실제로 터진 것들
```

작업이 끝나면 **§5 기록 의무**를 반드시 수행한다. 예외 없다.

---

## 1. 세팅 — spec-kit은 설치하지 않는다 ★

**팀원 각자가 spec-kit을 따로 깔 필요가 없다. 저장소 안에 이미 들어 있고 커밋되어 있다.**
`git clone` 하면 끝이다.

| 들어 있는 것 | 위치 | 커밋됨 |
|---|---|:---:|
| 헌법 · 템플릿 · 스크립트 | `.specify/` | ✅ (21개 파일) |
| **Codex용** speckit 명령 10종 | `.agents/skills/speckit-*/SKILL.md` | ✅ |
| **Claude Code용** speckit 명령 10종 | `.claude/skills/speckit-*/SKILL.md` | ✅ |
| 기능 명세 18종 | `specs/` | ✅ |

**두 도구 모두 설치 없이 바로 동작한다.** Codex를 쓰든 Claude Code를 쓰든 `git pull`이면 끝이다.

### 하지 말 것

> ❌ `pip install specify-cli` / `specify init` 를 **다시 실행하지 마라.**
> 재설치는 `.specify/memory/constitution.md`를 **빈 템플릿으로 덮어쓸 수 있다.**
> 우리 헌법 v1.3가 거기 들어 있다. 날아가면 전 파트가 근거를 잃는다.

### 필요한 것 (설치가 필요한 유일한 항목)

| 항목 | 비고 |
|---|---|
| **Codex CLI 또는 Claude Code** | 둘 중 아무거나. **저장소 루트에서** 실행한다 — 하위 폴더면 `.specify/`를 못 찾는다 |
| **bash** | 스크립트가 `.sh`다. **Windows는 Git Bash 또는 WSL**에서 돌린다 (두 도구 공통) |
| git | — |

### 30초 확인

```bash
bash .specify/scripts/bash/check-prerequisites.sh --json --paths-only
```

`REPO_ROOT / FEATURE_DIR / FEATURE_SPEC / IMPL_PLAN / TASKS` 가 나오면 정상이다.
에러가 나면 ① 루트에서 실행했는지 ② `.specify/feature.json`을 만들었는지(§2-3) 확인한다.

---

## 2. SDD 작업 흐름

### 2-1. 명령 이름

**이름은 같고 접두사만 다르다.** Codex는 `$`, Claude Code는 `/`.

| 명령 | Codex | Claude Code | 언제 |
|---|---|---|---|
| clarify | `$speckit-clarify` | `/speckit-clarify` | 모호한 부분을 질문으로 좁힌다 (spec의 C-xx 답할 때) |
| **plan** | `$speckit-plan` | `/speckit-plan` | 기술 계획 수립 — **각 파트의 시작점** |
| tasks | `$speckit-tasks` | `/speckit-tasks` | 작업 목록 생성 |
| analyze | `$speckit-analyze` | `/speckit-analyze` | spec·plan·tasks 일관성 점검 |
| implement | `$speckit-implement` | `/speckit-implement` | 구현 |
| checklist | `$speckit-checklist` | `/speckit-checklist` | 품질 체크리스트 |

**결과물은 두 도구가 같은 `specs/`에 쌓는다.** 누가 무엇으로 작업해도 한 곳에 모인다.

> **도구가 스킬을 인식하지 못하면** 이렇게 시키면 똑같이 동작한다:
> - Codex — *"`.agents/skills/speckit-plan/SKILL.md`를 읽고 거기 적힌 절차를 그대로 실행해."*
> - Claude Code — *"`.claude/skills/speckit-plan/SKILL.md`를 읽고 거기 적힌 절차를 그대로 실행해."*
>
> SKILL.md는 그 자체가 완전한 절차서라 별도 설치 없이 동작한다.

### 2-2. 순서

```text
spec.md 점검·확정  →  $speckit-plan  →  $speckit-tasks  →  $speckit-implement
   (요구사항)          (기술 선택)        (작업 분할)         (구현)
```

- **spec.md는 리드가 18종 전부 초안을 썼다.** 각 파트는 하단 **리뷰 3칸**을 채워 확정한다.
- **plan / tasks는 각 파트가 직접 생성한다.** 그 파트만 정확히 쓸 수 있어서 일부러 비워뒀다.
- Unity 파트 6종(002·006·013·014·017·018)은 **확정 완료**이며 013은 plan 부속 문서까지 있다.

### 2-3. ⚠️ 작업할 spec을 먼저 지정한다 (가장 흔한 실패 원인)

이 저장소의 spec은 `$speckit-specify`가 아니라 **손으로 만들었다.** 그래서 명령이 "지금 어느 spec인지"를 모른다.
**명령 실행 전에 매번 지정한다.**

```bash
echo '{ "feature_directory": "specs/013-avatar-customization" }' > .specify/feature.json
```

- 이 파일은 `.specify/.gitignore`에 의해 **커밋되지 않는다** — 사람마다 각자 만든다. 정상이다.
- 한 번만 쓸 거면 `export SPECIFY_FEATURE=specs/013-avatar-customization` 도 된다.

### 2-4. spec을 고치면 plan/tasks를 다시 만든다

| 무엇이 틀렸나 | 고칠 파일 |
|---|---|
| 요구사항 (무엇을 만들 것인가) | `spec.md` → **plan/tasks 재생성** |
| 기술 선택 (어떻게 만들 것인가) | `plan.md` |
| 작업 순서·분할 | `tasks.md` |
| 프로젝트 원칙 | **헌법** — 팀 합의 필요 |
| 파트 간 주고받는 형식 | §7 계약 절차 |

---

## 3. 헌법 — 반드시 걸리는 게이트

전문: `.specify/memory/constitution.md` (v1.3). 아래는 **실제로 사고가 났거나 나기 쉬운** 조항이다.

| 조 | 내용 | 어기면 |
|:---:|---|---|
| 2 | 게임 서버는 **실시간 상태만** 권위. Coin·Lease를 게임 서버가 바꾸지 않는다 | 경제 상태가 두 곳에서 갈라진다 |
| 5 | 아바타는 **식별자만 동기화**, 3D는 각 클라이언트가 로컬 생성. 캐릭터당 NetworkObject **하나** | 파츠마다 NetworkObject → 대역폭 폭발 |
| 8 | **endpoint 하드코딩 금지.** 서버 주소는 world-sessions 응답으로만 | 배포에서 접속 불가 |
| 9 | world-sessions는 **1차 MVP부터 목적 층 파라미터 포함** | 018에서 API를 다시 깬다 |
| 10 | `ai`/`back`/`front`/`game` 파트 브랜치 개별 CI/CD, `develop`은 실사용 기준. **Merge는 Squash** | — |
| 11·12 | 로그인은 **Google/Kakao/SSAFY 소셜 + 게스트만.** 자체 가입 없음. 게스트는 **비영속** | 범위 초과 |
| 13·14 | Refresh Token은 Unity·게임서버에 **절대** 전달 금지. 접속 토큰은 **서명 자체 검증** + 사용 식별자 기록 | Spring 장애가 월드 입장을 막는다 |
| 15 | **Secret 커밋 금지.** `.env.example`만 허용 | 즉시 사고 |
| 17 | RAG 검색은 **boothId+agentId 필터 강제.** 1건이라도 새면 릴리스 불가 | Critical Test 실패 |
| 21 | Layout 좌표 = **미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z, 시계방향 +** | 부스가 뒤집혀 배치된다 |
| 22 | 부스당 오브젝트 **최대 12개** | — |
| 23 | 아바타 외형은 **ID 집합.** 네트워크=고정 크기 struct, 저장 컬럼=**`TEXT`**. itemId는 **배열 인덱스 아님**. *"29~32자"는 무효* | **T-24 재발** (§6) |
| 25 | **텍스트 입력·외부 페이지는 React 레이어.** Unity는 트리거만. iframe 차단 시 **새 탭 fallback 필수** | 한글 IME 지옥 |
| 26 | **음성은 브라우저(WebRTC/SFU).** Unity Web은 마이크 불가, 게임 서버 경유 금지 | 구현 자체가 불가능 |
| 27 | **기준선 동결.** 동결 코드를 재구현·리팩터링하지 않는다 (`v0.0.1-poc`) | 되던 게 깨진다 |
| 29 | **개인 기록 의무** — 작업일지 / 트러블슈팅 / 매일 Jira 1회 | §5 |
| 30 | **미정 항목을 구현자가 임의 확정하지 않는다.** `docs/26_팀_결정_필요사항.md`에 올린다 | 나중에 통째로 뒤집힌다 |

---

## 4. 절대 하지 말 것

### 4-1. Git — 되돌릴 수 없는 명령

```text
❌ git push --force        ❌ git push -f
❌ git reset --hard        ❌ git clean -fd
❌ 원격 History 삭제 / 덮어쓰기
❌ 로컬 작업 파일 삭제
```

- **Non-fast-forward가 나면 force push하지 않는다.** `git pull --rebase` 또는 병합으로 푼다.
- 커밋/브랜치 규칙: `docs/17_Git_개발_Convention.md`. Merge는 **Squash** (헌법 10조).

### 4-2. Secret — 커밋하면 안 되는 값

`AWS_ACCESS_KEY` · `AWS_SECRET` · `api_key` · `secret` · `token` · `password` · private key ·
`.env` · 인증서(`*.pem` `*.p12` `*.key`).
**Mock 값과 인터페이스 이름은 괜찮다.** LLM 키는 GMS 지급 키를 Secret 저장소에서 주입한다 (헌법 15조).

### 4-3. 건드리지 않는 파일 / 폴더

| 대상 | 이유 |
|---|---|
| `reference/` | **READ ONLY.** 외부 레퍼런스 프로젝트. 수정 금지, 커밋 안 됨 |
| `festa-unity/Assets/_Project/Scripts/Network/Player/NetworkPlayer.cs` | 동결 기준선 (헌법 27조) |
| `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs` | 동결 기준선 |
| `festa-unity/Assets/_Project/Scripts/Booth/Runtime/BoothRuntime.cs` | 동결 기준선 |
| `festa-unity/Docker/` | 배포 인수인계 대상. 바꾸려면 Infra와 합의 |
| 벤더 에셋 원본 (`Assets/Rukha93/`, `Assets/Synty/`) | 원본 수정 금지. 필요하면 **복제해서** 쓴다 |
| `Library/` `Temp/` `Logs/` `obj/` `Builds/` | 탐색·커밋 대상 아님 |

> 013 아바타 재구현은 예외적으로 `World/Avatar/` 아래를 고치지만,
> **`NetworkPlayer` · Connection · Booth는 그대로 둔다.**

---

## 5. 기록 의무 (헌법 29조) — 예외 없음

| 언제 | 어디에 | 어떻게 |
|---|---|---|
| 작업 하나가 끝날 때마다 | `docs/24_작업일지.md` | 해당 **날짜 섹션에 즉시**. 없으면 만든다(최신이 위). 👤사람 / 🤖AI 구분 |
| 문제가 생길 때마다 | `docs/25_트러블슈팅.md` | **T-번호를 따서 등록** (증상/원인/해결/예방). **해결 못 했어도 등록한다** |
| 매일 작업 종료 시 | Jira | **1회 필수** 갱신 |

- 작업일지에는 **T-번호 링크만** 남긴다. 본문은 트러블슈팅에 쓴다.
- **세션 종료 전** 위 두 문서가 이번 세션 작업을 반영하는지 확인한다.
- AI에게 시킨 작업도 **똑같이** 기록한다.

---

## 6. Unity 함정 — 같은 실수를 반복하지 않는다

전문: `docs/25_트러블슈팅.md`. 아래는 **실제로 시간을 크게 태운 것들**이다.

| T | 증상 | 원인 → 대응 |
|---|---|---|
| **T-24** | 커스터마이징 버튼이 **무반응** | 인코딩 길이 초과를 **조용히 삼켜서** 값이 안 바뀜 → `OnValueChanged` 미발화. **실패를 삼키지 말고 로그+UI로 드러낸다** |
| **T-25** | 서버를 새로 빌드했는데 **계속 옛 동작** | 이름이 다른 옛 컨테이너가 포트 점유. **이름이 아니라 포트로 확인**한다 |
| **T-26** | `docker build`가 `chmod: cannot access '/app/festa-unity.x86_64'` | Dockerfile이 `COPY . /app` → **빌드 컨텍스트가 `Builds/linux-server`여야 한다** (§7) |
| **T-27** | 파츠를 바꾸면 **T포즈로 굳음** | 런타임 캐릭터 생성 시 `runtimeAnimatorController`가 null이면 설정되지 않음 → **폴백 체인 + 명시적 에러 로그** |
| T-07 | WebGL에서 로딩이 영원히 안 끝남 | **WebGL에서 `Task.Delay`는 완료되지 않는다.** `Awaitable` 사용 |
| T-08 | 런타임 생성 오브젝트가 전부 **마젠타** | 머티리얼 셰이더 미지정 → 런타임 머티리얼은 **URP Lit 명시** |
| T-19 | `WebAssembly streaming compilation failed` | 웹서버 `Content-Encoding` 헤더 불일치 |
| T-21 | `SerializeField` 이름 바꿨더니 **Inspector 값 전부 소실** | 직렬화 이름이 키다. 바꾸면 `FormerlySerializedAs` |
| T-22 | WebGL 빌드에서 **IMGUI 한글이 안 보임** | 기본 폰트에 한글 글리프 없음 → 폰트 명시. 애초에 **텍스트 UI는 React** (헌법 25조) |
| T-23 / T-05 | 코드를 고쳤는데 **결과가 그대로** | 빌드 산출물·브라우저 캐시·서버 컨테이너 중 하나가 옛것. **세 개를 다 의심한다** |
| T-15 | `NetworkVariable` 동작 안 함 | **필드로 선언**해야 한다 (프로퍼티 불가) |
| T-18 | Sidekick 등 에디터 창이 안 열림 | `cloned player systems` — 멀티플레이 플레이모드 클론에서는 못 연다 |

### 코드 규칙 요약

- **실패를 조용히 삼키지 않는다.** 기본값으로 되돌리지 말고 로그+에러 상태로 드러낸다 (T-24가 이것 때문에 났다).
- 정적 Booth 오브젝트는 **NetworkObject 금지** (Local Spawn, 헌법 4조).
- 텍스트 입력 UI는 Unity가 아니라 **React 오버레이** (헌법 25조).
- Coin·Lease 등 영구 상태의 Source of Truth는 **Spring** (헌법 1조).
- **파일 역할 주석** — 새 구현 파일(설정·스타일 제외) 최상단에 "이 파일이 시스템에서 왜 존재하는가"를
  한 줄로 남긴다. 기준: React/프레임워크 관례를 모르는 사람이 파일명·위치만으로 이 파일의 역할을
  못 알아볼 때만. 이미 있는 WHY 주석(출처·근거)과는 별개로 공존 가능 — 그건 "왜 이렇게 짰나",
  이건 "이 자리가 시스템에서 뭐 하는 자리인가". 예: `main.tsx` → "React 앱을 브라우저 DOM에 최초
  마운트하는 진입점". 대상 아님: package.json·tsconfig·vite.config 등 웹 개발 전반에 보편적인
  설정 파일, index.css.

---


### Unity 작업 상시 규칙 (game 파트에서 실전으로 확정 — 전 파트 공통 적용)

- **씬·프리팹·.meta 파일은 텍스트로 직접 편집하지 않는다.** `.unity`·`.prefab`·`.asset`·`.meta` 는
  GUID 참조가 얽힌 YAML 이다. 텍스트로 고치면 참조가 끊기고 씬이 열리지 않는다.
  씬/오브젝트/컴포넌트 변경은 **Unity MCP 도구로만** 한다. MCP 가 응답하지 않으면(에디터 꺼짐)
  멈추고 사용자에게 에디터를 켜달라고 요청한다.
- **에셋 미사용 판정은 GUID 검색으로 끝내지 않는다.** 다음 셋은 GUID 참조가 없어도 사용 중이다:
  ① `Resources/` (코드가 경로로 로드 — 옮기면 컴파일은 통과하고 런타임에 조용히 null)
  ② 이름으로 찾는 셰이더 (`Shader.Find("Festa/Avatar/GarmentTint")` 등)
  ③ `ProjectSettings/` 참조 (URP 파이프라인·QualitySettings·Input Actions).
  판정은 `AssetDatabase.GetDependencies` 로 하고, 모델에 임베드된 머티리얼→텍스처 링크는
  텍스트 검색에 안 보이므로 Unity 로 확인한다.
- 에셋 이동은 `AssetDatabase.MoveAsset` 으로만 한다. 탐색기·`mv` 로 옮기면 참조가 끊긴다.
- `Assets/Plugins/WebGL/` 은 Unity 규약 폴더다 — `.jslib` 는 여기 있어야 WebGL 빌드에 포함된다.
- **기준선 동결 준수** — `docs/23_기준선_동결_워크플로.md`. 동결된 기준선 코드를 재구현·리팩터링하지
  않는다. 현재 기준선: `v0.0.1-poc`.
- Unity 변경 후에는 Editor Refresh/Compile 과 Console Error 를 먼저 확인한다. 사용자가 명시하지
  않은 Play Mode 전환·WebGL/Linux 빌드는 실행하지 않는다.
- 로컬 시각 QA 산출물(`festa-unity/Assets/Screenshots/`)은 소스 에셋이 아니며 커밋하지 않는다.
  근거로 남길 스크린샷은 `docs/<이니셜>/verify/` 에 커밋한다.
- `festa-unity/Library/`·`Temp/`·`Obj/`·`Build/`·`Logs/` 는 생성물이다 — 읽지도 쓰지도 않는다.
- **에디터에서 통과해도 빌드에서 깨지는 부류가 있다** (`docs/KHS/28` §0-5): 런타임 메시 조작
  (`isReadable` 필요), 셰이더 변형, `Resources.Load`, IMGUI, `ProfilerRecorder`. 이 목록에
  걸리는 변경은 반드시 빌드로 확인한다.

## 7. 검증된 실행 명령

```bash
# Unity Linux 서버 Docker 이미지 — 빌드 컨텍스트는 Builds/linux-server (T-26)
docker build -t festa-world:dev -f Docker/Dockerfile Builds/linux-server

# 옛 컨테이너 정리 — 이름이 아니라 포트로 찾는다 (T-25)
docker ps -a --filter "publish=7777"
docker rm -f <거기서_나온_컨테이너_이름>

docker run -d --name festa-world-01 -p 7777:7777 festa-world:dev
```

인수인계 상세: `festa-unity/Docs/deployment-handoff.md` / 상태: `festa-unity/Docs/poc-status.md`

---

## 8. 파트 간 계약 — 혼자 바꾸면 안 되는 것

| 계약 | spec | 합의 대상 |
|---|---|---|
| **Layout JSON** (좌표·필드) | 005 · 006 · 016 | FE + BE + Unity |
| Unity → React 상호작용 payload | 006 · 008 · 016 | FE + Unity |
| SSE 이벤트 payload | 008 | AI + FE |
| 아바타 외형 데이터(ID·저장 형식) | 013 | Unity + BE |
| world-sessions 응답 · 층 파라미터 | 002 · 018 | BE + Unity |

**변경 절차** (헌법 24조): 영향 파트 확인 → 문서 수정 → DTO 변경 → Consumer 수정 → 통합 테스트 → MR에 Breaking Change 표시.

> ⚠️ **Layout 좌표는 규칙만 확정됐고 아직 검증되지 않았다.**
> React가 오브젝트 1개짜리 Layout을 보내고 Unity에서 같은 위치인지 **눈으로 보는 왕복 1회**가 남아 있다.
> 부호 하나는 반드시 틀린다는 전제로 하는 게 안전하다.

> ⚠️ **BE 주의**: 아바타 컬럼을 `VARCHAR(32)`로 만들면 **T-24가 DB에서 재발한다.** `TEXT`로 만든다 (헌법 23조).
> 옛 계약서 `festa-unity/Docs/avatar-customization-contract.md`는 **폐기**됐다.
> 유효한 계약은 `specs/013-avatar-customization/contracts/` 3종뿐이다.

---

## 9. 프로젝트 지도

```text
SSAFESTA/
├── AGENTS.md                       ← 이 파일 (에이전트 규칙의 단일 출처, 두 도구 공통)
├── CLAUDE.md                       ← 요약 + 이 파일로 안내 (Claude Code 진입점)
├── .specify/
│   ├── memory/constitution.md      ★ 헌법 v1.3
│   ├── templates/  scripts/bash/
│   └── feature.json                ★ 작업 중인 spec 지정 (커밋 안 됨, 각자 생성)
├── .agents/skills/speckit-*/       Codex 명령      ($speckit-plan)
├── .claude/skills/speckit-*/       Claude Code 명령 (/speckit-plan)
├── specs/
│   ├── README.md                   spec 18종 목록 + 확정 사항
│   └── NNN-이름/{spec,plan,tasks}.md
├── docs/
│   ├── 00_SDD_가이드.md             ★ 사람이 읽는 SDD 입문서
│   ├── 17_Git_개발_Convention.md    커밋·브랜치 규칙
│   ├── 23_기준선_동결_워크플로.md    동결 기준: v0.0.1-poc
│   ├── 24_작업일지.md 25_트러블슈팅.md  ★ 기록 의무
│   ├── 26_팀_결정_필요사항.md        미정 항목은 여기로 (헌법 30조)
│   ├── 29_아바타_커스터마이징_작업지시.md  013 구현 지시
│   └── sdd/parts/{BE,FE,AI,INFRA}.md
├── festa-unity/                    Unity 프로젝트
└── reference/                      READ ONLY (커밋 안 됨)
```

---

## 10. 막히면

| 상황 | 어디로 |
|---|---|
| 결정이 없어서 막힘 | `docs/26_팀_결정_필요사항.md`에 등록 + 리드에게 알림. **혼자 정하지 않는다** |
| 기술 문제로 막힘 | 본인 트러블슈팅에 **먼저 기록**하고 공유 (같은 문제를 둘이 겪지 않게) |
| spec이 틀린 것 같음 | spec 하단 리뷰 ②칸에 적는다. **그러라고 만든 칸이다** |
| speckit 명령이 안 돌아감 | §1 확인(루트에서 실행? bash 있나?) → §2-3 `feature.json` 지정 확인 |
| Codex / Claude Code가 스킬을 못 찾음 | §2-1 하단의 "SKILL.md를 읽고 실행" 방식으로 우회 |
