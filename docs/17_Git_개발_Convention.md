# SSAFY FESTA Git / 개발 Convention

> **목적**: 여러 파트가 동시에 개발할 때 Branch, Commit, MR, 코드 스타일, 환경설정 충돌을 줄이기 위한 공통 규칙이다.  
> 팀 GitLab 정책에 맞게 일부 명칭은 변경할 수 있으나 핵심 규칙은 프로젝트 시작 시 확정한다.

---

## 1. Repository 전략

프로젝트 구조에 따라 Mono Repo 또는 분리 Repo 모두 가능하다.

분리 Repo 예:

```text
festa-frontend
festa-backend
festa-ai
festa-unity
festa-infra
```

Mono Repo 예:

```text
frontend/
backend/
ai/
unity/
infra/
docs/
```

현재 팀 운영 방식에 맞게 하나를 선택한다. 중간에 이유 없이 구조를 바꾸지 않는다.

> **✅ 팀 결정 (2026-08-12)**: Repo 구성은 **파트별 자율 결정**. 단, 브랜치/CI-CD 전략(§2-1)과 develop 병합 규칙은 전 파트 공통이다.

---

## 2. Branch 전략

기본:

```text
main
└─ develop
   ├─ feature/*
   ├─ fix/*
   ├─ refactor/*
   ├─ docs/*
   └─ chore/*
```

### main

- 시연/배포 가능한 안정 버전
- 직접 Push 금지

### develop

- 통합 개발 Branch
- MR을 통해 Merge

### feature

```text
feat/S15P21A604-87-boothslot-list
feature/S15P21A604-75-avatar-persist
```

> **✅ 개정 (2026-08-24)**: develop/main 으로 향하는 작업 브랜치에는 **Jira Key 가 필수**다.
> develop/main 대상 MR 의 제목·브랜치 키는 MR 리뷰에서 사람이 검증한다 (CI 러너 없음). Jira 상태 전이는 Webhook→Jira Automation('진행 중')과 내장 연동('완료')이 담당한다.
> 상세: `docs/jira-gitlab-workflow.md`

### fix

```text
fix/S15P21A604-241-duplicate-lease
```

### hotfix

발표 직전 main 긴급 수정처럼 실제 필요가 있을 때만 사용한다.

---

## 2-1. 파트 브랜치 + CI/CD 전략 ✅ (2026-08-12 팀 결정)

기본 전략(§2) 위에 파트 통합 브랜치 계층을 둔다:

```text
main
└─ develop            ← 실사용 환경 기준 CI/CD (전 컴포넌트 통합 배포 + 통합 헬스체크)
   ├─ ai              ← 파트 브랜치: 각자 CI/CD + 개발환경 "개별" 배포
   ├─ back
   ├─ front
   ├─ game
   │    └─ feature/FESTA-xxx-...   ← 작업 브랜치는 자기 파트 브랜치에서 분기
```

규칙:

1. **파트 브랜치(ai/back/front/game)는 각각 CI/CD를 가진다** — push 시 자체 빌드·테스트 후 해당 파트의 개발환경에 자동 배포된다. 파트끼리 서로의 배포를 기다리지 않는다.
2. **develop은 실제 사용 환경 기준으로 CI/CD한다** — 완료된 상태만 파트 브랜치에서 develop으로 병합하며, develop을 일상 작업장으로 쓰지 않는다.
3. feature/fix 브랜치는 자기 파트 브랜치에서 분기하고 자기 파트 브랜치로 MR한다.
4. 파이프라인 상세 사양은 `docs/sdd/parts/INFRA.md` (infra-001)에서 spec으로 관리한다.
5. **공유 문서·spec 통합 (#24·#59, 2026-08-23 채택)**
   - **쓰기**: 각 파트가 **자기 변경분만** develop PR로 올려 누적한다. 한 사람이 남의 변경분을
     해석해 옮기지 않는다.
   - **읽기 기준**: develop이 정본이며, 파트 브랜치가 develop을 따라간다.
   - **파트 경계를 넘는 결정**은 이슈에서 **반영 owner 1명**을 지정해 그 사람이 develop에 쓴다.

---

## 3. Branch Naming

```text
<type>/<jira-key>-<short-description>
```

영문 kebab-case 권장. 허용 type (2026-08-24 확정):

```text
feat feature fix refactor test docs chore build ci hotfix perf
```

좋음:

```text
feat/S15P21A604-42-booth-publish
```

나쁨:

```text
hyungsoon-work
final-final
new-feature-2
```

---

## 4. Commit Message

형식:

```text
<type>(<scope>): <summary>
```

예:

```text
feat(booth): add published layout endpoint
fix(unity): prevent duplicate player spawn
refactor(ai): separate rag search service
test(wallet): add duplicate reward test
docs(api): update consultation contract
```

> **✅ 개정 (2026-08-25)**: GitLab 내장 Jira 연동이 켜져 **커밋 메시지가 Jira 를 직접 움직인다.**
> 이전 권장이 **필수**로 바뀌었다.
>
> | 커밋 메시지 | 결과 |
> |---|---|
> | `feat(auth): 로그인 API 연동 (S15P21A604-123)` | Jira 이슈에 **커밋 링크 + 코멘트** 자동 추가 |
> | `Closes S15P21A604-123` (본문 아무 줄) | 위 + 그 커밋이 **`develop` 에 도달할 때 '완료' 전환** |
>
> - **모든 커밋에 이슈 키를 넣는다.** 키가 없으면 Jira 에 아무 기록도 남지 않는다.
> - **`Closes` 는 그 작업으로 이슈가 끝날 때만** 쓴다. 중간 커밋에 쓰면 머지 시 미완료 이슈가 닫힌다.
> - `Closes` 를 파트 브랜치에 적어도 그 순간에는 전환되지 않는다 — **`develop` 도달 시점**이다.
> - 커밋 언어는 기존대로 **한국어**를 유지한다.
> - 키의 **필수** 지점은 브랜치명과 develop/main 대상 MR 제목이다 (`docs/jira-gitlab-workflow.md` §4).
>
> 예:
> ```text
> perf(unity): 아바타 스킨메시 결합 — 렌더러 11→7 (S15P21A604-236)
>
> 신체 파츠 5개가 같은 재질·같은 골격이라 무손실 결합.
>
> Closes S15P21A604-236
> ```

### type

| Type | 의미 |
|---|---|
| feat | 기능 |
| fix | 버그 |
| refactor | 동작 변경 없는 구조 개선 |
| test | 테스트 |
| docs | 문서 |
| chore | 설정/빌드/잡무 |
| perf | 성능 |
| ci | CI/CD |
| build | 빌드 시스템 |

---

## 5. Commit 원칙

- 하나의 Commit에는 하나의 논리 변경을 담는다.
- 동작 변경과 대규모 포맷팅을 같은 Commit에 섞지 않는다.
- Secret을 Commit하지 않는다.
- 빌드 산출물/캐시를 불필요하게 Commit하지 않는다.
- Unity `.meta`는 Asset과 함께 관리한다.

---

## 6. Merge Request 제목

```text
[S15P21A604-123][BE] Booth Lease API 구현
[S15P21A604-201][UNITY] Player Spawn/Despawn 구현
```

Jira Title Prefix와 유사하게 맞춘다.
**develop/main 대상 MR 은 제목 또는 source branch 에 Jira Key 가 반드시 있어야 한다.** GitLab CI 가 아니라 MR 리뷰 규칙으로 확인한다 (러너 없음, 2026-08-26).

---

## 7. MR Template

> **✅ 개정 (2026-08-24)**: 저장소에 실제 템플릿이 있다 — `.gitlab/merge_request_templates/Default.md`.
> MR 작성 화면에서 Description → **Choose a template → Default** 를 선택하면 자동 적용된다.
> 아래는 참고용 구형이다.

```markdown
## 변경 내용

- 

## 관련 Issue

- FESTA-

## 테스트

- [ ] 로컬 실행
- [ ] 관련 테스트 통과
- [ ] 주요 Regression 확인

## API / 데이터 변경

- 없음 / 있음:

## Screenshot / Video

- UI/Unity 변경 시 첨부

## 리뷰 포인트

-
```

---

## 8. Merge 조건

최소:

- CI 성공
- Conflict 없음
- 최소 1명 Review 권장
- Jira 완료 조건 충족
- API 계약 변경 시 관련 문서 반영
- DB Migration이 있으면 Migration 포함

발표 직전에는 불필요한 대규모 Refactor MR을 금지한다.

---

## 9. Merge 방식

> **✅ 팀 결정 (2026-08-12)**: `Squash Merge`로 통일한다 (이의 제기 시 재논의).
> **✅ 시행 (2026-08-24)**: GitLab 프로젝트 설정 `squash_option = default_on` 적용 — MR 머지 시 Squash 가 기본 체크된다.

장점:

- feature branch의 중간 WIP Commit을 정리
- Jira 단위 History 확인 용이

---

## 10. Rebase / Pull 규칙

작업 시작:

```bash
git checkout develop
git pull
git checkout feature/...
```

오래된 Branch는 MR 전 최신 develop을 반영한다.

팀이 Git에 익숙하지 않다면 강제 Rebase보다 Merge Conflict를 안전하게 해결하는 것을 우선한다.

---

## 11. Conflict 처리

Conflict가 발생하면 파일을 가장 잘 아는 담당자가 직접 해결한다.

특히:

- Unity Scene
- Prefab
- ProjectSettings
- package-lock
- DB migration

Conflict는 단순히 `Accept Incoming All`로 처리하지 않는다.

---

## 12. Unity Git 규칙

### 반드시 Commit

- Assets
- Packages
- ProjectSettings
- `.meta`

### 제외

- Library
- Temp
- Logs
- obj
- Build output (`Builds/`)
- UserSettings(팀 필요 여부 검토)
- IDE 생성 파일 (`.vscode/`, `.idea/`, `*.csproj`, `*.sln`, `*.slnx`)

### festa-unity `.gitignore` 확정본

프로젝트 루트에 실제 파일로 포함되어 있다 (`festa-unity/.gitignore`).

```gitignore
# Unity generated
[Ll]ibrary/
[Tt]emp/
[Oo]bj/
[Ll]ogs/
[Uu]ser[Ss]ettings/
[Mm]emoryCaptures/

# Build output — 빌드 결과물은 절대 커밋하지 않는다
[Bb]uilds/
*.apk
*.aab
*.unitypackage

# IDE
.vscode/
.idea/
*.csproj
*.sln
*.slnx
*.user

# OS
.DS_Store
Thumbs.db
```

주의: 빌드 출력 폴더는 반드시 프로젝트 루트의 `Builds/` 아래에 만든다.
`Assets/` 안에 빌드하면 Unity가 결과물을 에셋으로 재임포트해서 프로젝트가 깨진다.

### Scene / Prefab

- 동시에 같은 Scene을 여러 명이 수정하지 않도록 작업 소유권을 나눈다.
- World Scene과 Booth Prefab 작업을 분리한다.
- 가능한 경우 Nested Prefab / Additive Scene으로 충돌을 줄인다.

---

## 13. Backend Java Convention

### Naming

```text
Class: PascalCase
Method/Variable: camelCase
Constant: UPPER_SNAKE_CASE
Package: lowercase
```

### Layer 예

```text
controller
service
repository
domain
dto
config
exception
```

### 규칙

- Controller에 비즈니스 로직을 몰지 않는다.
- Entity를 API Response로 직접 노출하지 않는다.
- Transaction 경계를 Service에 명확히 둔다.
- Error Code를 문자열로 곳곳에 하드코딩하지 않는다.

---

## 14. Frontend TypeScript Convention

### Naming

```text
Component: PascalCase
Hook: useCamelCase
Function/Variable: camelCase
Constant: UPPER_SNAKE_CASE 또는 프로젝트 규칙 통일
Type/Interface: PascalCase
```

### 규칙

- `any` 남용 금지
- API DTO와 View Model을 필요하면 분리
- 동일 endpoint 문자열을 여러 Component에 중복 작성하지 않음
- 공통 UI는 shared component로 이동

---

## 15. Unity C# Convention

> **✅ 팀 결정 (2026-08-12)**: private field는 `_camelCase`로 확정 (POC 코드 전체가 이미 사용 중).

```text
Class / Method / Property: PascalCase
private field: _camelCase (확정)
local variable: camelCase
constant: PascalCase 또는 UPPER_SNAKE_CASE 중 Unity 팀 규칙 통일
Interface: IInteractable
```

### 권장

- `MonoBehaviour`가 모든 로직을 직접 가지지 않게 역할 분리
- Prefab가 Service Locator를 무분별하게 직접 찾지 않음
- `FindObjectOfType` 반복 사용 금지
- Network 로직과 View 로직 분리

---

## 16. Python / FastAPI Convention

```text
module/function/variable: snake_case
Class: PascalCase
constant: UPPER_SNAKE_CASE
```

### 구조 후보

```text
app/
├─ api/
├─ services/
├─ models/
├─ schemas/
├─ repositories/
├─ providers/
└─ core/
```

LLM Provider 호출을 endpoint 함수 안에 직접 길게 작성하지 않는다.

---

## 17. API 변경 규칙

API 계약 변경 시:

1. 영향 파트 확인
2. API 문서 수정
3. DTO 변경
4. Consumer 수정
5. 통합 테스트
6. MR 설명에 Breaking Change 표시

Frontend/Unity가 이미 사용하는 Field를 말없이 삭제하지 않는다.

---

## 18. DB Migration 규칙

- Schema 변경은 Migration 파일로 관리한다.
- 운영/공용 DB를 수동으로만 수정하고 기록을 남기지 않는 방식 금지.
- 이미 적용된 Migration 파일을 임의 수정하지 않고 새 Migration을 추가한다.

Migration tool은 Backend 팀 선택에 따른다.

---

## 19. 환경변수

Repository에는 `.env.example`만 둔다.

예:

```text
DB_HOST=
DB_PORT=
JWT_SECRET=
AI_API_URL=
S3_BUCKET=
```

실제 Secret은 GitLab CI Variable / AWS Secret 저장소 등을 사용한다.

---

## 20. 코드 리뷰 기준

리뷰 우선순위:

1. 기능 요구 충족
2. 데이터 손상 / 중복 처리
3. 인증·인가
4. 예외 처리
5. 테스트
6. 가독성
7. 미세한 스타일

스타일 지적만 길게 하고 핵심 동작 결함을 놓치지 않는다.

---

## 21. 금지 사항

- `main` 직접 Push
- Secret Commit
- Jira 없이 며칠짜리 대형 작업 진행
- API를 Consumer 통보 없이 Breaking Change
- Unity Scene 대량 수정 후 설명 없는 Merge
- 발표 직전 대규모 구조 변경
- 테스트하지 않은 hotfix를 main에 바로 반영

---

## 21-1. SDD (spec-kit) 사용

저장소 루트에 spec-kit 0.16.3 골격이 설치되어 있다. **추가 설치가 필요 없다.**

```text
.specify/memory/constitution.md   프로젝트 헌법 v1.1
.specify/scripts/bash/            명령이 호출하는 스크립트
.claude/skills/speckit-*/         Claude Code 용
.agents/skills/speckit-*/         Codex 용
specs/NNN-name/                   기능별 spec / plan / tasks
```

작업 전 **대상 spec을 지정**한다 (손으로 만든 spec이라 명령이 자동 인식하지 못한다):

```bash
echo '{ "feature_directory": "specs/004-booth-slot-lease" }' > .specify/feature.json
```

이후 `/speckit-plan` → `/speckit-tasks` → `/speckit-implement` 순으로 진행한다.
상세는 `specs/README.md` 참조.

---

## 21-2. spec 상태 갱신 절차 (#59, 2026-08-23 채택)

C-xx 상태의 SSOT는 Clarifications 표 1곳이다 (`docs/00` §2).

1. **갱신은 덧붙이기가 아니라 교체** — "대기 → ✅ 확정" 마커를 쌓지 않고, 낡은 문장을
   최종 상태 문장으로 다시 쓴다(내용 누락 없이). 변경 이력은 Git/Issue/PR이 담당한다.
2. **완료 기록과 현재 상태를 구분한다** — `tasks.md`의 `[X]` 항목처럼 *그때의 사실*을 적은 기록은
   지우면 이력이 사라지고 그냥 두면 현재값으로 오독된다. 원문을 유지하고 *(→ 이후 …로 변경, T0xx)*
   화살표로 최신 상태를 잇는다. 반대로 **현재 상태를 말하는 문장**(Clarifications 셀·구조 설명·필드 표)은
   1번대로 교체한다 (#58·#62 적용 사례).
3. **C-xx 확정의 완료 조건**은 체크박스 하나가 아니라 3단계다: 상태 변경 → 관련 서술 교체 →
   **해당 ID로 파일 전체 grep, 전 occurrence 확인**. 이관·rename도 같다 — 옛 경로·옛 값
   잔존을 grep으로 확인한다 (#43 `feature_directory` 잔존 사례).
4. **파일 단위 반입(`git checkout <branch> -- <path>`)은 그 파일이 이미 깨져 있어도 그대로 옮긴다.**
   반입 전후로 **머지 후 삭제(D) 목록**(T-117)만 보지 말고 **파일 내부 중복**도 확인한다 —
   `grep -c '^## '`로 섹션 수를 세거나 헤더 목록을 눈으로 본다. `docs/22`의 같은 섹션이
   1 → 3 → 4벌로 늘어난 것이 이 경로였다 (2026-08-23 확인).

---

## 22. Release Tag

시연 안정 버전은 Tag를 남긴다.

```text
v0.0.1-poc
v0.1.0-mvp1
v0.2.0-mvp2
v1.0.0-demo
```

실제 SemVer를 엄격히 따를지보다 “어떤 버전이 시연 가능했는지” 추적하는 것이 중요하다.

태그를 만드는 시점과 절차(검증 → 문서 교정 → 커밋 → 태그 → 선언)는
**`23_기준선_동결_워크플로.md`**를 따른다. AI 에이전트에게 작업을 시킬 때의
지시 템플릿도 같은 문서에 있다.
