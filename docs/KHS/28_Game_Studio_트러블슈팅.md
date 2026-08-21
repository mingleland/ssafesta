# FESTA Game Studio 트러블슈팅

> **범위**: spec 020, GameProject/Preview/Event 계약, Web 2D Studio/Runtime, Spring Game API,
> FESTA Host/Portal 연동 작업에서 발생한 문제.
>
> 새 문제는 `GS-T001`, `GS-T002` 순서로 번호를 올리고 증상/원인/해결/예방을 모두 기록한다.
> 기존 일반 일지의 T-162~T-168은 아래 `GS-T001~GS-T007`로 이동했다.

## 2026-08-21

### GS-T022. 격리 복제본에서 기존 GitHub 인증과 저장소 소유권을 바로 사용할 수 없음 (해결)

- **증상** — 격리 계정으로 만든 독립 복제본에서 GitHub push는 Windows 자격 증명을 찾지 못했고, 기존 사용자 권한으로 실행하면 반대로 저장소 소유권 검사가 중단했다.
- **원인** — 작업 파일 소유자는 Codex 격리 계정이고 GitHub 자격 증명은 Windows 사용자 keyring에 있어 두 실행 컨텍스트의 접근 범위가 달랐다.
- **해결** — 불필요한 재로그인을 취소하고 기존 Windows 사용자 인증을 사용하는 승인된 Git 명령에 해당 복제본을 명령 단위 `safe.directory`로만 지정해 push했다. 전역 Git 설정은 변경하지 않았다.
- **예방** — 격리 복제본을 원격 반영할 때는 계정 로그아웃으로 단정하지 않고 먼저 실행 사용자·keyring·저장소 소유자를 구분한다. 인증 재발급보다 기존 사용자 컨텍스트와 명령 단위 안전 경로를 우선한다.

### GS-T021. Foundation/develop 기준선에 Frontend 소스가 없어 구현 기준이 불명확함 (해결)

- **증상** — `feature/game-studio-foundation`과 당시 로컬 `develop`에는 `festa-frontend/`가 없어 문서에 적힌 T010~T026 파일을 만들 수 없었다.
- **원인** — Frontend 소스는 파트 브랜치에서 관리되고 공통 develop 통합은 보류된 상태인데, 로컬 원격 참조도 최신 `front`보다 뒤처져 있었다.
- **해결** — 과거 검증된 Frontend snapshot에서 구현을 시작하되 원격 fetch 후 최신 `github/front`를 확인하고, 완료한 Game Studio 4개 커밋만 최신 `front` 위로 rebase했다. 재검증 결과 충돌과 타 파트 경로 변경은 0건이다.
- **예방** — 파트 구현은 공통 spec 브랜치가 아니라 해당 파트의 최신 원격 브랜치를 기준선으로 사용하고, 첫 push 전 반드시 원격 fetch·ahead/behind·변경 경로를 재검증한다.

### GS-T020. npm 기본 캐시가 작업공간 밖이라 의존성 조회가 실패함 (해결)

- **증상** — Vitest 버전 조회와 설치가 `AppData/Local/npm-cache` 임시 디렉터리 생성 `EPERM`으로 실패했다.
- **원인** — 격리 실행 계정에 기본 사용자 npm 캐시 경로 쓰기 권한이 없었다.
- **해결** — 작업공간 내부 전용 `.npm-cache`를 명시해 Vitest 4.1.11을 설치하고 lockfile을 정상 갱신했다.
- **예방** — 격리 작업공간에서 npm을 사용할 때는 처음부터 쓰기 가능한 프로젝트 외부 전용 캐시 경로를 명시하고 캐시는 커밋하지 않는다.

### GS-T019. 제한 권한의 가상 병합 검사가 Git 객체 기록 단계에서 실패함 (해결)

- **증상** — `git merge-tree --write-tree origin/develop HEAD`가 저장소 object database에 객체를 추가할 권한이 부족하다는 오류로 중단됐다.
- **원인** — Game Studio worktree의 Git object database는 공유 저장소 아래에 있고, 일반 검증 컨텍스트에는 해당 `.git/objects` 쓰기 권한이 없었다.
- **해결** — 같은 읽기 전용 병합 검증을 승인된 저장소 권한으로 다시 실행해 merge tree 생성과 무충돌 결과를 확인했다. 실제 브랜치나 작업 파일은 변경하지 않았다.
- **예방** — `merge-tree --write-tree`처럼 이름과 달리 임시 Git 객체를 기록하는 검증 명령은 처음부터 저장소 object database 권한이 있는 컨텍스트에서 실행한다.

### GS-T018. 원격 게시 후 develop 재정렬로 로컬·원격 feature 이력이 갈라짐 (해결)

- **증상** — 원격에 게시한 `feature/game-studio-foundation`이 최신 develop보다 3커밋 뒤였고, rebase 후 로컬은 원격 기준 ahead/behind가 동시에 표시됐다.
- **원인** — Game Studio 원격 게시 뒤 PR #29 등 공통 문서 커밋이 develop에 추가되어 최신 기준선 재정렬이 필요했다.
- **해결** — 기존 tip을 `backup/game-studio-pre-issue-sync-20260821`에 보존하고 최신 `origin/develop` 위로 충돌 없이 rebase한 뒤, `--force-with-lease`로 소유 feature 브랜치만 원격 갱신했다.
- **예방** — 원격 feature를 rebase할 때는 사전 backup, 원격 fetch, develop 가상 병합, `--force-with-lease` 순서를 지키고 공유 파트 브랜치에는 강제 push하지 않는다.

### GS-T017. 큰 문서 패치가 마지막 문맥 불일치로 전체 실패함 (해결)

- **증상** — data-model과 상위 아키텍처 문서를 여러 구간 한 번에 바꾸던 패치가 마지막 예상 문장 불일치 때문에 적용되지 않았다.
- **원인** — 긴 원자 패치 안에 서로 떨어진 문맥을 묶었고 실제 줄바꿈·문장이 예상과 달랐다.
- **해결** — 파일에 변경이 없음을 확인한 뒤 줄 번호와 실제 인접 문장을 다시 읽고 Aggregate·Entity·State, 문서별로 작은 패치로 나눠 적용했다.
- **예방** — 기존 대형 문서는 먼저 대상 구간을 출력하고 의미 단위별 패치를 사용한다. 한 패치에 독립 파일·멀리 떨어진 구간을 과도하게 묶지 않는다.

### GS-T016. Game Studio와 Backend ERD spec 번호가 019로 충돌함 (해결)

- **증상** — #21 답변에서 `specs/019-game-studio`와 `origin/back`의 `specs/019-erd-schema`가 같은 번호를 사용 중인 것이 확인됐다.
- **원인** — Backend spec이 `specs/README.md`에 등록되지 않아 Game Studio 생성 시 전체 원격 브랜치의 경로까지 보이지 않았다.
- **해결** — 모든 원격 브랜치의 spec 019~022 경로를 검색하고 비어 있는 020으로 Game Studio 디렉터리·참조·Spec-Kit feature 경로를 이동했다.
- **예방** — 신규 spec 번호는 develop의 인덱스뿐 아니라 `git ls-tree`로 모든 활성 원격 브랜치를 검색한 뒤 배정하고 `specs/README.md`에 즉시 등록한다.

### GS-T015. 전용 worktree의 Git 메타데이터 접근이 제한됨 (해결)

- **증상** — 임시 전용 worktree에서 `git update-index --refresh`가 저장소 object database 접근 권한 오류로 실패했다.
- **원인** — worktree 파일은 임시 경로에 있지만 공용 `.git` 메타데이터는 원 저장소 아래에 있어 제한된 실행 컨텍스트의 쓰기 범위와 달랐다.
- **해결** — 파일 편집은 전용 worktree 안에서 유지하고, Git index·rebase·commit 작업만 동일 사용자 권한의 승인된 컨텍스트에서 실행했다.
- **예방** — 별도 worktree 사용 시 작업 파일 경로와 Git 메타데이터 경로가 다름을 전제로 하고, Git 상태 변경은 처음부터 저장소 범위가 명확한 동일 컨텍스트에서 수행한다.

### GS-T014. 리베이스 도중 origin/develop이 한 커밋 더 진행됨 (해결)

- **증상** — 첫 리베이스 완료 직후 `origin/develop`이 `462c4a2`에서 `41b119b`로 진행되어 기능 브랜치가 다시 1커밋 뒤가 됐다.
- **원인** — 작업 중 다른 문서 PR이 develop에 병합됐다.
- **해결** — 변경 경로와 충돌 가능성을 다시 확인하고 Game Studio 9개 커밋을 최신 `41b119b` 위로 한 번 더 리베이스했다.
- **예방** — 장시간 문서 작업은 최종 검증 직전에 원격 참조와 ahead/behind를 다시 확인하고, PR 전 최신 develop 기준 가상 병합을 반복한다.

### GS-T013. develop 리베이스에서 삭제된 일반 일지와 공통 결정 문서가 충돌함 (해결)

- **증상** — 10개 커밋을 develop 위로 옮기는 동안 `docs/KHS/24`, `25`, `README`와 `docs/26_팀_결정_필요사항.md`에서 modify/delete 또는 내용 충돌이 반복됐다.
- **원인** — 이전 game 기준선에는 일반 KHS 기록이 있었지만 최신 develop에는 없었고, docs/26은 develop과 Game Studio가 각각 최신 항목을 추가한 상태였다.
- **해결** — 일반 24/25는 develop의 삭제 상태를 유지하고 Game Studio 전용 27/28만 보존했다. docs/26은 최신 develop의 아바타 결정을 유지하면서 Game Studio #20~#22 행을 수동 병합했다. KHS README는 존재하는 27/28만 가리키도록 축소했다.
- **예방** — 공통 문서 충돌은 파일 전체의 ours/theirs를 선택하지 않고 행 단위로 병합한다. Game Studio 기록은 27/28 외 문서에 중복하지 않는다.

### GS-T012. feature 브랜치가 game 기준선 이력 2,057개 Unity 경로를 포함함 (해결)

- **증상** — 작업 파일은 문서뿐인데 `origin/develop...feature/game-studio-foundation` 비교에는 Unity 경로 2,057개와 총 2,126개 변경이 표시됐다.
- **원인** — 브랜치를 당시의 `game` 커밋 `8c116f1`에서 만들었기 때문에 Game Studio 커밋과 무관한 Unity/game 이력이 조상으로 포함됐다.
- **해결** — 원본 tip을 `backup/game-studio-gamebase-20260821`로 보존하고, Game Studio 전용 9개 커밋만 최신 `origin/develop` 위로 리베이스했다. 재검증 결과 Unity 변경 경로는 0개다.
- **예방** — 여러 파트가 소비하는 spec·공통 계약 브랜치는 항상 최신 `origin/develop`에서 만들고 전용 worktree를 사용한다. 커밋 전 기준 브랜치 대비 경로 분포를 확인한다.

### GS-T011. Dialogue Choice 이동 필드가 Runtime과 JSON Schema에서 서로 다른 위치에 정의됨 (해결)

- **증상** — reference Runtime은 선택지의 `choice.nextNodeId`를 읽지만 JSON Schema는 같은 필드를 Event 객체 속성으로 허용하고 있었다.
- **원인** — 초기 schema 작성 시 Dialogue Choice 전이와 Event 전이를 혼동해 속성 위치가 잘못 배치됐다.
- **해결** — `nextNodeId`를 Dialogue Choice 속성으로 이동하고, terminal Action과 `nextNodeId`를 동시에 쓰는 잘못된 fixture를 추가해 `DIALOGUE_NEXT_WITH_TERMINAL_ACTION`으로 거부하도록 했다.
- **예방** — 계약 필드 추가 시 schema·fixture·reference Runtime 세 곳의 실제 접근 경로를 대조하고 positive/negative trace를 함께 갱신한다.

### GS-T010. 공유 작업트리가 Game Studio 브랜치가 아니어서 전용 문서 조회가 실패함 (해결)

- **증상** — `specs/020-game-studio`와 `docs/KHS/27`, `28` 문서를 현재 경로에서 읽으려 했으나 파일이 없다는 오류가 발생했다.
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
