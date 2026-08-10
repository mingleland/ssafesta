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
feature/FESTA-123-booth-layout-save
feature/FESTA-201-player-spawn
```

Jira Key가 있으면 포함한다.

### fix

```text
fix/FESTA-345-duplicate-lease
```

### hotfix

발표 직전 main 긴급 수정처럼 실제 필요가 있을 때만 사용한다.

---

## 3. Branch Naming

```text
<type>/<jira-key>-<short-description>
```

영문 kebab-case 권장.

좋음:

```text
feature/FESTA-42-booth-publish
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
[FESTA-123][BE] Booth Lease API 구현
[FESTA-201][UNITY] Player Spawn/Despawn 구현
```

Jira Title Prefix와 유사하게 맞춘다.

---

## 7. MR Template

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

팀 규모에서는 `Squash Merge`를 권장할 수 있다.

장점:

- feature branch의 중간 WIP Commit을 정리
- Jira 단위 History 확인 용이

다만 팀이 개별 Commit History를 중요하게 쓰면 일반 Merge도 가능하다. 시작 시 한 방식으로 통일한다.

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

```text
Class / Method / Property: PascalCase
private field: camelCase 또는 _camelCase 중 팀에서 하나로 통일
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
