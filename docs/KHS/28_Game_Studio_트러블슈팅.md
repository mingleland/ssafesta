# FESTA Game Studio 트러블슈팅

> **범위**: spec 019, GameProject/Preview/Event 계약, Web 2D Studio/Runtime, Spring Game API,
> FESTA Host/Portal 연동 작업에서 발생한 문제.
>
> 새 문제는 `GS-T001`, `GS-T002` 순서로 번호를 올리고 증상/원인/해결/예방을 모두 기록한다.
> 기존 일반 일지의 T-162~T-168은 아래 `GS-T001~GS-T007`로 이동했다.

## 2026-08-21

### GS-T010. 공유 작업트리가 Game Studio 브랜치가 아니어서 전용 문서 조회가 실패함 (해결)

- **증상** — `specs/019-game-studio`와 `docs/KHS/27`, `28` 문서를 현재 경로에서 읽으려 했으나 파일이 없다는 오류가 발생했다.
- **원인** — 다른 작업이 진행되면서 공유 작업트리의 현재 브랜치가 `feature/game-studio-foundation`에서 `game`으로 변경되었고, Game Studio 문서는 아직 기능 브랜치에만 존재했다.
- **해결** — 현재 `game` 작업트리를 전환하거나 되돌리지 않고 Git object에서 기능 브랜치 문서를 확인한 뒤, 별도 Git worktree에 `feature/game-studio-foundation`을 checkout하여 기록을 갱신했다.
- **예방** — Game Studio 후속 작업은 전용 worktree에서 수행하고, 파일 조회 전에 현재 branch와 worktree 목록을 먼저 확인한다.

## 2026-08-20

### GS-T001. GitHub CLI 인증이 일반 샌드박스에서만 무효로 표시됨 (해결)

- **증상** — 브라우저 인증을 완료했는데 일반 세션의 `gh auth status`는 토큰이 무효라고 표시했다.
- **원인** — 인증 정보가 Windows 자격 증명 저장소에 있었고, 제한된 실행 컨텍스트에서는 같은 저장소를 정상적으로 읽지 못했다.
- **해결** — 동일 사용자 권한의 승인된 실행 컨텍스트에서 인증 상태를 다시 확인하고 이슈 생성·조회·수정을 수행했다.
- **예방** — Windows의 `gh` 인증 문제는 반복 로그인 전에 같은 권한 컨텍스트인지 확인하고, 인증 확인과 실제 명령을 같은 컨텍스트에서 실행한다.

### GS-T002. 기본 Python과 JSON Schema 검증 패키지가 없어 계약 검증이 중단됨 (우회)

- **증상** — `python`이 PATH에 없었고 번들 Python에는 `jsonschema` 패키지가 없어 fixture의 JSON Schema 검증을 실행할 수 없었다.
- **원인** — 프로젝트 의존성이 아닌 Codex 실행 환경의 도구·패키지 구성 차이다.
- **해결** — PowerShell로 Schema와 fixture의 JSON 문법을 검증하고, 외부 패키지가 필요 없는 Node 의미 검증기(`validate-fixtures.mjs`)로 ID 참조·Scene 유형·Tile 크기·Component 중복을 검사했다.
- **예방** — 계약 테스트 도구를 정할 때 실행기와 validator 패키지 존재를 먼저 확인한다. 정식 FE/BE 구현에서는 각 파트 의존성에 JSON Schema validator를 명시적으로 고정한다.

### GS-T003. 여러 문서의 한 번짜리 패치가 한 파일의 문맥 불일치로 전체 실패함 (해결)

- **증상** — 상위 문서 8개를 한 번에 갱신하려던 패치가 아키텍처 문서의 예상 문장과 실제 문장이 달라 아무 파일에도 적용되지 않았다.
- **원인** — 서로 다른 문서의 문맥 anchor를 하나의 원자적 패치에 묶어 한 곳의 불일치가 전체 변경을 막았다.
- **해결** — 각 문서의 실제 heading과 인접 문장을 다시 확인하고 작은 패치 단위로 나눠 적용했다.
- **예방** — 여러 기존 문서를 갱신할 때는 먼저 정확한 anchor를 검색하고, 독립 문서는 별도 패치로 적용해 실패 범위를 제한한다.

### GS-T004. AI 이슈에 Frontend 담당자가 잘못 지정됨 (해결)

- **증상** — AI 담당 GitHub 계정을 전달받지 못한 상태인데 #22의 assignee에 Frontend 담당 `ghkim1632`가 지정되어 있었다.
- **원인** — 본문 참조 태그와 실제 assignee 역할을 분리하지 못하고 기존 파트 계정을 재사용했다.
- **해결** — #20 Frontend 이슈에는 `ghkim1632`와 `colosair`를 모두 지정하고, #22에서는 `ghkim1632` assignee를 제거해 AI 담당 계정 미지정 상태로 되돌렸다.
- **예방** — 이슈 생성 전 `파트 → 실제 GitHub login → assignee/mention` 매핑을 확인하고, 담당 계정이 없으면 임의 지정하지 않는다.

### GS-T005. Spec-Kit Bash 스크립트가 Windows 샌드박스에서 실행되지 않음 (해결)

- **증상** — 기본 `bash`는 WSL 인스턴스 생성 `E_ACCESSDENIED`, Git Bash 직접 호출은 `dirname: command not found`, login shell은 `/c/Users/SSAFY` 쓰기 거부로 `setup-plan.sh`가 연속 실패했다.
- **원인** — `C:\Windows\System32\bash.exe`는 WSL launcher였고, Git Bash는 non-login 호출 시 Unix tool PATH가 초기화되지 않았다. login shell의 MSYS 경로는 제한된 샌드박스가 workspace write 경로로 인식하지 못했다.
- **해결** — `C:\Program Files\Git\bin\bash.exe -lc`를 승인된 동일 사용자 컨텍스트에서 실행해 공식 `setup-plan.sh`와 `setup-tasks.sh`를 완료했다.
- **예방** — Windows에서 Spec-Kit 스크립트는 WSL alias가 아닌 Git Bash login shell을 사용하고, `/c/...` 경로 쓰기가 막히면 정확한 저장소 경로로 권한 실행한다.

### GS-T006. 파트 브랜치의 프로젝트 루트를 저장소 루트로 잘못 조회함 (해결)

- **증상** — `origin/front:package.json`, `origin/back:build.gradle` 조회가 경로 없음으로 실패했다.
- **원인** — 실제 파트 루트가 각각 `festa-frontend/`, `backend/`인데 저장소 루트에 manifest가 있다고 가정했고 Backend가 Gradle일 것이라고 추측했다.
- **해결** — `git ls-tree`로 브랜치 디렉터리를 확인한 뒤 `festa-frontend/package.json`과 `backend/pom.xml`을 읽어 npm/Vite와 Maven/Spring Boot 구성을 확인했다.
- **예방** — 다른 브랜치 기술스택은 파일명을 추측하지 말고 `git ls-tree --name-only <ref>`로 실제 프로젝트 루트와 build tool부터 찾는다.

### GS-T007. apply_patch에서 같은 파일을 한 패치 안에서 삭제·재추가할 수 없음 (해결)

- **증상** — reference validator 전체 교체를 `Delete File`과 `Add File`로 한 패치에 넣자 `multiple operations target` 오류로 적용되지 않았다.
- **원인** — `apply_patch`는 동일 경로에 여러 파일 작업을 한 패치에서 허용하지 않는다.
- **해결** — 삭제 패치와 추가 패치를 순서대로 분리해 적용하고, 즉시 fixture runner를 실행해 5/5 결과를 확인했다.
- **예방** — 파일 전체 교체는 delete/add를 두 번으로 나누거나 기존 내용에 Update patch를 사용한다.

### GS-T008. 완료 작업 수가 docs/22와 tasks.md에서 13개·14개로 달라짐 (해결)

- **증상** — `tasks.md`는 T068까지 완료되어 14개인데 `docs/22_다음_할일.md`에는 13개로 기록되어 있었다.
- **원인** — docs/22에 13개를 쓴 직후 T068 자체를 완료 처리하면서 총계가 하나 늘었지만 같은 문장의 숫자를 다시 갱신하지 않았다.
- **해결** — 실제 `[x]` task 수를 다시 계산해 docs/22를 14개로 수정했다.
- **예방** — 완료 수는 수기로 추정하지 않고 `tasks.md`의 `[x]` 항목을 계산한 결과와 대조한 뒤 기록한다.

### GS-T009. 공유 작업트리의 기록 이동 변경이 동시에 진행된 Unity 커밋에 포함됨 (해결)

- **증상** — 일반 24/25 문서에서 Game Studio 내용을 이동하던 중 다른 세션이 `513e66c` Unity 작업을 커밋했고, 아직 별도로 커밋하지 않은 24/25 이동 안내도 그 커밋에 함께 들어갔다.
- **원인** — 여러 세션이 같은 working tree와 index를 공유하는 상태에서 한 세션의 파일 변경이 다른 세션의 commit 시점에 보였다.
- **해결** — 이미 만들어진 Unity 커밋을 amend/reset하지 않고 보존했다. 새 27/28 문서, agent 기록 규칙, spec 019 경로 갱신만 별도 Game Studio 문서 커밋으로 준비하고 staged 파일 목록을 다시 확인한다.
- **예방** — 동시 작업이 예상되면 파트별 `git worktree`를 사용한다. 같은 working tree를 쓸 때는 커밋 직전 `git diff --cached --name-only`로 다른 세션 파일이 섞이지 않았는지 확인한다.
