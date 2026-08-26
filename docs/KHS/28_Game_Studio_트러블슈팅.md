# FESTA Game Studio 트러블슈팅

> **범위**: spec 019, GameProject/Preview/Event 계약, Web 2D Studio/Runtime, Spring Game API,
> FESTA Host/Portal 연동 작업에서 발생한 문제.
>
> 새 문제는 `GS-T001`, `GS-T002` 순서로 번호를 올리고 증상/원인/해결/예방을 모두 기록한다.
> 기존 일반 일지의 T-162~T-168은 아래 `GS-T001~GS-T007`로 이동했다.

## 2026-08-25

### GS-T064. GitLab Issue 목록 API에 지원하지 않는 `order_by=iid`를 사용함 (해결)

- **증상** — 병합 전 논의 이슈의 중복 여부를 조회하는 GitLab API가 `order_by does not have a valid value`를 반환해 issue 목록만 비어 있고 같은 명령의 member 조회만 성공했다.
- **원인** — Merge Request 응답의 IID 정렬 관행을 Issue 목록 API에도 그대로 적용해, 해당 GitLab 버전이 허용하지 않는 `order_by=iid`를 전달했다. 서로 독립인 두 REST 결과를 한 출력에 묶어 부분 성공도 함께 나타났다.
- **해결** — 서버 `order_by`를 제거하고 `state=opened&per_page=100`으로 조회한 뒤 PowerShell에서 `Sort-Object iid`를 적용했다. 기존 #48·#55·#56·#69·#73·#78·#81·#101을 확인해 중복 신규 이슈 대신 통합 #104와 기존 이슈별 연결 댓글 구조를 선택했다.
- **예방** — GitLab API resource별 허용 정렬 필드를 동일하다고 가정하지 않는다. 목록 조회 실패를 다른 API 결과와 섞지 않고 즉시 실패 처리하며, 작은 프로젝트에서는 정렬 없는 전체 페이지를 받아 client-side IID 정렬로 검증한다.

### GS-T063. Atlassian API token을 Bearer로 사용해 Jira 조회가 403으로 거부됨 (해결)

- **증상** — `JIRA_API_TOKEN`과 `ATLASSIAN_API_TOKEN`을 Bearer header로 사용한 `/rest/api/3/myself` 요청이 모두 403을 반환했다. 첫 진단 명령은 PowerShell `foreach` 결과를 직접 pipe하면서 parser 오류도 발생했다.
- **원인** — 현재 제공된 Atlassian token은 OAuth access token이 아니라 Atlassian API token이라 Jira Cloud Basic 인증의 `email:token` 조합이 필요했다. loop statement 결과도 현재 PowerShell에서는 변수에 먼저 수집해야 한다.
- **해결** — 저장소 Git 사용자 이메일과 `ATLASSIAN_API_TOKEN`을 Basic 인증으로 조합하고 `/myself`에서 활성 강형순 계정과 accountId를 확인했다. loop 결과는 `$rows`에 수집했다. 이후 15건 담당자 PUT이 모두 성공했고 `assignee=currentUser()` JQL 재조회로 검증했다.
- **예방** — Atlassian API token과 OAuth Bearer token을 구분한다. 계정 변경 전 `/myself`로 displayName·accountId·active를 확인하고, 변경 뒤 동일 JQL로 대상 수와 담당자를 재검증한다. Secret 값은 출력하거나 파일에 기록하지 않는다.

### GS-T062. 활성 Game Studio 사양의 이슈 링크 일부가 이전 GitHub를 계속 가리킴 (해결)

- **증상** — GitLab 이관 후에도 현재 계약·계획·미완료 task의 #69·#73·#78 링크가 이전 GitHub 저장소를 열었다. 작업일지의 과거 GitHub 활동 기록과 활성 추적 링크가 구분되지 않았다.
- **원인** — 구현·검증 기준을 갱신하면서 이슈 번호와 상태는 유지했지만 문서 URL의 호스트 이관 여부를 별도 검사하지 않았다.
- **해결** — GitLab API로 #69·#73·#78·#81의 동일 IID와 제목·OPEN 상태를 확인했다. 현재 사양의 계약·계획·task 링크만 GitLab work item으로 교체하고, 당시 작업 사실을 기록한 과거 일지의 GitHub PR/Issue 링크는 역사적 증거로 보존했다.
- **예방** — 저장소 이관 뒤 문서 링크 검사는 현재 정본 문서와 역사 일지를 나눠 수행한다. 현재 사양은 새 tracker를 사용하고, 과거 기록은 대상과 시점을 왜곡하지 않도록 원래 링크를 유지한다.

### GS-T061. PowerShell URI 보간이 MR IID와 query 구분자를 합쳐 상태 재검증이 실패함 (해결)

- **증상** — GitLab MR의 최종 merge status를 다시 조회하는 명령이 코드·문서 MR 모두 `merge_request_iid is invalid`를 반환했다. URI를 고친 뒤에는 `foreach` statement 바로 뒤에 pipe를 연결한 출력 구문이 `empty pipe element` parser 오류를 냈다.
- **원인** — PowerShell expandable string에서 `$iid?with_merge_status_recheck=true`를 사용해 `?` 앞의 loop 변수를 명시적으로 닫지 않았다. 생성된 URI에 정상 IID가 들어가지 않았고, statement 결과를 괄호나 변수로 수집하지 않은 채 바로 pipe하려 한 구문도 현재 PowerShell parser에서 유효하지 않았다.
- **해결** — query string 직전 변수를 `${iid}`로 감싼 `merge_requests/${iid}?with_merge_status_recheck=true` 형식으로 바꿨다. `foreach` 결과는 `$rows`에 먼저 수집한 뒤 출력하고, REST 오류는 즉시 실패 처리했다. 최종 확인에서 MR !8·!9 모두 `mergeable`, 충돌 없음, Draft 아님을 반환했다.
- **예방** — PowerShell에서 변수 바로 뒤에 URL query 또는 식별 문자가 오면 `${variable}` 문법을 사용한다. 여러 MR을 검사할 때는 loop 출력을 변수에 수집한 뒤 pipe하고, HTTP 실패 응답을 null 결과로 계속 출력하지 않고 해당 URI와 오류를 바로 확인한다.

### GS-T060. 테스트·빌드 병렬 경쟁에서 100ms 편집 gate가 일시 초과함 (측정 경계 확정)

- **증상** — 전체 Vitest, TypeScript/Vite build, lint를 동시에 실행한 검증에서 최대 fixture Object 이동 최악값이 120.25ms로 100ms gate를 한 차례 넘었다. 같은 test file 단독 실행과 build 종료 뒤 전체 suite는 통과했다.
- **원인** — 500 Object·10,000 Tile·실제 Sprite Component를 가진 fixture의 immutable parse 검증이 CPU를 쓰는 동안 별도 `tsc`와 Vite transform을 같은 장비에서 동시에 수행해 wall-clock 시간이 늘었다. 제품 편집 동작과 CI 빌드 경합을 같은 표본으로 섞었다.
- **해결** — 100ms 기준이나 assertion을 완화하지 않았다. 성능 test file을 단독 재실행한 뒤 CPU 경쟁이 없는 전체 `npm test`에서도 통과함을 확인하고, build·lint는 각각 성공시켰다. 활성 브라우저 FPS는 기존 T065 사람 QA로 분리된 상태를 유지했다.
- **예방** — wall-clock 성능 gate는 같은 장비의 대형 build와 병렬 실행하지 않는다. 기능 회귀는 병렬화할 수 있지만 100ms 기준과 활성 탭 FPS는 측정 조건·foreground·동시 프로세스를 기록하고 격리해서 판정한다.

### GS-T059. 전체 맵 맞춤에서 10,000 Tile DOM이 다시 생기고 Canvas가 폭보다 커짐 (해결)

- **증상** — 100×100 맵에서 `전체`를 누르면 zoom 표시는 15%였지만 map child의 min-content가 stage 폭을 밀어내 viewport가 전체를 담지 못했다. 폭을 100%로 고정하자 이번에는 전체 Tile이 화면 안으로 판정되어 span 10,000개가 생성됐다.
- **원인** — `.gss-map-canvas`에 명시적인 `width:100%`가 없었고, 기존 viewport culling은 화면에 실제로 보이는 모든 Tile을 렌더하는 규칙이라 전체 맞춤에서는 자연스럽게 상한 전체가 visible이 됐다. 편집 배율 가상화와 전체 overview가 같은 렌더 경로를 사용했다.
- **해결** — map child 폭을 stage에 고정했다. zoom 25% 이하에서는 TileLayer를 최대 1,000px의 단일 Canvas에 pixelated 합성하고 Tile span은 0개로 유지한다. 1:1로 돌아오면 기존 viewport/2칸 overscan span 경로로 자동 전환한다. 브라우저 실측은 전체 15%에서 Tile DOM 0/10,000 합성, 1:1에서 640/10,000이었다.
- **예방** — 가상화 완료 조건은 스크롤 상태뿐 아니라 “전체 보기”처럼 모든 데이터가 viewport에 들어오는 상태를 포함한다. DOM 편집 표현과 저배율 overview 표현을 분리하고 badge/data attribute로 어느 경로인지 관찰 가능하게 한다.

### GS-T058. 검증 도구의 명령·locator 가정을 그대로 사용해 첫 실행이 실패함 (해결)

- **증상** — Vitest에 Jest 전용 `--runInBand`를 넘겨 unknown option으로 종료됐고, 첫 JSX wrapper에는 scroll div 닫기 하나가 빠져 build가 실패했다. 브라우저의 `전체` 버튼도 팔레트와 toolbar에 둘 존재해 exact role locator가 strict mode로 거부됐으며 해당 runtime에는 `getByTitle` helper가 없었다.
- **원인** — 다른 test runner와 일반 Playwright API를 현재 프로젝트·인앱 브라우저 wrapper에도 동일하게 사용할 수 있다고 가정했다. UI wrapper 구조 변경은 unit test만 먼저 실행해 TypeScript JSX parse를 뒤에서 발견했다.
- **해결** — 표준 `npm test`로 전환하고 JSX closing tag와 hook dependency를 build/lint 결과에 맞춰 수정했다. 브라우저는 `캔버스 보기` role group 안의 `전체` 버튼으로 범위를 좁혔다. 이후 49 files/269 tests, build, lint와 실제 클릭 여정을 모두 통과했다.
- **예방** — 검증 명령은 `package.json` script를 정본으로 사용한다. UI 구조 변경은 unit test만이 아니라 `tsc -b`와 lint를 같은 묶음에서 확인하고, 중복 한국어 label은 상위 landmark/group으로 locator 범위를 좁힌다.

### GS-T057. PLATFORMER 최대 크기와 TileLayer 저장 상한이 달라 레이어 추가 시 계약 오류가 발생함 (FE 차단, 계약 결정 대기)

- **증상** — PLATFORMER width/height는 각각 200×100까지 허용하지만 JSON Schema와 Frontend validator의 TileLayer `data`는 10,000개가 상한이다. 200×100 빈 Scene은 만들어지지만 Tile Layer 추가 순간 20,000개 배열이 생성되어 저장·검증이 실패한다.
- **원인** — Scene 축별 크기 상한과 row-major TileLayer 배열 상한을 서로 독립적으로 정했고 `width×height` 조합 제약을 Authoring에 두지 않았다.
- **해결** — Backend wire/schema 상한을 일방적으로 20,000으로 늘리지 않았다. 공통 `maxTileCellsPerLayer=10,000`을 validator와 Authoring command가 함께 사용하고 Inspector는 초과 cell 수를 한국어로 표시하며 적용을 비활성화한다. imported large Scene의 레이어 추가도 같은 설명으로 거부한다. 200×50 유효 경계와 200×100 거부를 unit test로 고정했다.
- **예방** — 축별 크기, 직렬화 배열 크기, JSON byte, DB 저장 비용은 하나의 limit matrix로 검토한다. 20,000칸을 지원하려면 FE만 바꾸지 않고 shared schema·Backend validator·부하 기준을 [GitLab #101](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/101)에서 승인한다.

### GS-T056. 큰 맵을 작은 카드에 압축하고 모든 Object·Tile DOM을 동시에 생성함 (해결)

- **증상** — Scene width가 커져도 Canvas 폭은 610~1240px에 머물러 한 칸이 몇 px로 줄었고, 최대 500 Object button과 최대 10,000 Tile span을 화면 밖까지 한 번에 만들었다. 배치 위치를 읽기 어렵고 scroll·선택 render 비용도 맵 전체에 비례했다.
- **원인** — zoom을 실제 cell 크기가 아니라 고정 stage의 백분율로 적용했고, Canvas가 scroll viewport의 grid 범위를 알지 못했다. Object layer도 검색 결과 500개 article을 즉시 렌더했다.
- **해결** — stage 폭을 `Scene width×32px×zoom`으로 계산하고 작은 맵만 480px 최소 폭을 사용한다. `ResizeObserver`와 scroll frame에서 visible grid를 계산해 2칸 overscan Object/Tile만 렌더하고, 레이어 선택으로 원거리 Object를 중앙 탐색한다. 레이어 목록은 검색 정합성을 유지하며 80개 단위로 표시한다. 100×50/5,000 Tile 브라우저 실측에서 전체 5,000개 대신 위치별 460~598개 span만 생성됐다.
- **예방** — 대형 편집기 성능 테스트는 reducer 시간뿐 아니라 Canvas pixel 크기, viewport/scroll 범위, 실제 Object/Tile DOM 수, 화면 밖 선택 복귀를 함께 측정한다.

### GS-T055. 광범위한 `Reference/` ignore 규칙이 신규 Runtime 성능 파일을 첫 커밋에서 숨김 (해결)

- **증상** — 성능 요약 테스트와 수정된 Runtime은 커밋됐지만 새 `runtime/reference/framePerformance.ts`가 commit stat에 없었다. 일반 `git status`에도 ignored untracked 파일이라 나타나지 않아 그대로 push하면 import 대상이 누락될 상태였다.
- **원인** — 저장소 루트 `.gitignore`의 `Reference/` 패턴이 대소문자 구분 없이 모든 하위 `reference` 디렉터리에 적용됐다. 기존 Runtime reference 파일은 이미 tracked라 수정이 들어갔지만 신규 파일만 무시됐다.
- **해결** — `git check-ignore -v`로 정확한 규칙과 경로를 확인하고 신규 파일 하나만 `git add -f -- <exact-path>`로 포함했다. push 전 커밋을 amend해 9개 파일과 신규 module 4개가 모두 들어간 것을 `git show --stat`으로 재확인했다.
- **예방** — ignored 경로 아래 tracked code에 신규 파일을 만들면 일반 status만 보지 않고 `git check-ignore -v`와 commit stat을 확인한다. 강제 추가는 확인된 단일 파일에만 사용하며 광범위한 `-f`나 ignore 규칙 변경으로 우회하지 않는다.

### GS-T054. PowerShell cmdlet 뒤 `$LASTEXITCODE` 검사로 문서 검증이 조용히 조기 종료됨 (해결)

- **증상** — JSON schema parse, fixture, Runtime trace, task 집계를 한 명령에서 실행했지만 exit 0과 빈 출력만 남고 뒤 검증 결과가 표시되지 않았다.
- **원인** — `ConvertFrom-Json`은 PowerShell cmdlet이라 native process용 `$LASTEXITCODE`를 새로 설정하지 않는다. 직후 `$LASTEXITCODE -ne 0`을 검사해 null/이전 값으로 분기했다.
- **해결** — cmdlet 성공은 `$?`, 이어지는 `node` 프로세스는 각각 `$LASTEXITCODE`로 구분해 검사했다. fixture 7/7, Runtime trace 6/6, task 94/86/8을 실제 출력으로 재확인했다.
- **예방** — PowerShell cmdlet과 native executable을 연속 검증할 때 같은 exit 변수로 묶지 않는다. cmdlet은 `$?` 또는 `try/catch`, native command는 호출 직후 `$LASTEXITCODE`를 사용한다.

### GS-T053. 로컬 안전 복구 보조 버튼의 글자가 밝은 기본 배경에서 보이지 않음 (해결)

- **증상** — DOM과 기능 테스트에서는 `JSON 보관`, `임시본 버리기`가 정상 존재했지만 실제 screenshot에서 두 버튼이 흰 사각형처럼 보여 문구를 식별하기 어려웠다. 기본 동작 버튼만 녹색 배경이라 정상으로 보였다.
- **원인** — 복구 dock이 기존 충돌 dock의 크기·배치를 재사용하면서 보조 버튼의 background/border/text color를 명시하지 않았다. 상위 전역 button 스타일의 밝은 배경과 낮은 대비가 적용됐다.
- **해결** — 복구 dock의 보조 버튼을 짙은 녹색 계열 배경·테두리와 밝은 문자로 고정하고 primary 버튼도 흰 문자 대비를 명시했다. 동일 화면을 다시 캡처해 세 버튼 문구가 모두 보이는지 확인했다.
- **예방** — 새 dialog/dock은 DOM snapshot만으로 완료하지 않고 실제 screenshot에서 기본·보조·위험 버튼의 문자 대비, toast 겹침, 작은 창 배치를 함께 확인한다.

### GS-T052. 브라우저 QA를 실 API 모드로 띄우고 종료 중인 Port를 즉시 재사용함 (해결)

- **증상** — 첫 5175 서버에서 게스트 입장이 편집 route로 이어지지 않았고, Mock 모드로 재시작할 때 5175가 사용 중이라 Vite가 5176으로 이동했다.
- **원인** — Game Studio 편집 route는 회원 전용인데 첫 서버에 `VITE_USE_MOCK=true`를 주지 않았다. 기존 PTY에 Ctrl+C를 보낸 직후 프로세스 종료가 확정되기 전에 같은 Port를 재사용했다.
- **해결** — 임의 인증 주입이나 route guard 변경 없이 공식 Mock selector로 5176 서버를 띄우고 Google mock callback·테스트 닉네임으로 회원 세션을 만들었다. 실제 선택 Port에서 Scene/Clipboard/Tile/Collider UI를 검증했다.
- **예방** — 브라우저 QA 명령은 처음부터 quickstart의 Mock 환경변수와 명시 Port를 사용한다. 종료 요청 뒤 session exit 또는 Port 해제를 확인하고, Vite가 대체 Port를 선택하면 브라우저 URL도 출력값 기준으로 바꾼다.

### GS-T051. Vitest 단일 파일 filter에 Frontend workdir 경로를 중복 지정함 (해결)

- **증상** — `festa-frontend`를 작업 디렉터리로 둔 상태에서 `festa-frontend/src/.../authoringCommands.test.ts`를 filter로 넘겨 `No test files found`가 발생했다.
- **원인** — 저장소 root 기준 파일 경로와 npm 실행 workdir 기준 경로를 혼용했다. Vitest include는 `src/**/*.test.ts`라 앞의 `festa-frontend/`가 중복됐다.
- **해결** — filter를 `src/game-studio/__tests__/unit/authoringCommands.test.ts`로 수정해 11개를 통과시키고, 이후 전체 44 files / 252 tests를 다시 실행했다.
- **예방** — npm script를 하위 workdir에서 실행할 때 filter와 fixture 경로는 그 workdir 상대 경로로 표기한다. 단일 테스트 성공 뒤 반드시 filter 없는 전체 suite를 실행한다.

### GS-T050. 최신 GitLab fetch에서 저장소 단위 safe.directory를 첫 호출에 누락함 (해결)

- **증상** — 최신 `front` 확인을 위한 승인된 `git fetch gitlab --prune`이 `detected dubious ownership`으로 중단됐다.
- **원인** — 기존 GS-T046의 재발 방지 규칙을 알고 있었지만 첫 승인 명령에 명령 단위 `safe.directory`를 포함하지 않았다. Sandbox 소유 worktree와 승인 명령 사용자가 달랐다.
- **해결** — 전역 Git 설정을 바꾸지 않고 `git -c safe.directory=<현재 Game Studio worktree> fetch gitlab --prune`으로 다시 실행해 최신 ref를 회수했다.
- **예방** — 이 두 Game Studio worktree의 escalated Git 호출은 처음부터 저장소 절대경로를 포함한 `git -c safe.directory=...` 형태로만 실행한다. 재시도 절차가 아니라 기본 명령 템플릿으로 고정한다.

## 2026-08-24

### GS-T047. 공유 worktree object store의 임시 객체를 이관 원본으로 오인할 위험 (해결)

- **증상** — 이관 전 저장소 실측에서 `git count-objects -vH`가 약 630.46 MiB pack과 함께 `tmp_obj_*` garbage 140개, 약 28.96 MiB를 보고했다. connectivity 검사는 성공했지만 현재 worktree 전체를 그대로 미러링하면 로컬 backup ref와 임시 객체까지 이관 범위로 오해할 수 있었다.
- **원인** — 여러 worktree가 하나의 object database를 공유하고 `merge-tree --write-tree` 검증을 반복하면서 참조되지 않는 임시/dangling 객체가 남았다. 이는 GitHub branch/tag가 가리키는 이력 손상이 아니라 로컬 작업 저장소 상태다.
- **해결** — `git fsck --connectivity-only` exit 0으로 참조 이력 무결성을 확인했다. 현재 worktree에서 `git gc`·`git prune`·`git push --mirror`를 실행하지 않고, 실제 이관은 GitHub Import 또는 별도 fresh clone에서 수행하도록 이관 준비 문서에 명시했다.
- **예방** — 이관 source of truth는 local object directory가 아니라 원격 branch/tag ref와 SHA로 정의한다. 공유 worktree 정리는 이관과 분리하고, branch/tag 수·핵심 SHA·tag를 Import 전후 대조한다.

### GS-T046. 승인된 Git fetch가 사용자 경계 변경으로 dubious ownership에 실패 (해결)

- **증상** — 최신 GitHub ref를 확인하기 위해 승인된 네트워크 권한으로 `git fetch`를 실행하자, 직후 모든 Git segment가 `detected dubious ownership`으로 중단됐다.
- **원인** — worktree는 sandbox 사용자가 만들었지만 승인된 명령은 desktop 사용자로 실행됐다. 기존 GS-T045와 같은 저장소 소유권 경계였고, 첫 명령에 저장소 단위 `safe.directory`를 주지 않았다.
- **해결** — 전역 설정을 수정하지 않고 각 Git 호출에 `-c safe.directory=<현재 worktree>`를 적용해 fetch와 ahead/behind/merge-base 확인을 완료했다.
- **예방** — 승인 경계 밖에서 worktree Git 명령을 실행할 때는 첫 segment부터 모든 `git` 호출에 명령 단위 `safe.directory`를 붙인다. `git config --global`로 사용자 전체 신뢰 범위를 넓히지 않는다.

### GS-T045. chained Git 명령의 두 번째 segment에 safe.directory가 적용되지 않음 (해결)

- **증상** — 문서 PR의 최신 develop 가상 병합 검사에서 fetch는 성공했지만 뒤이어 실행한 `merge-tree`가 dubious ownership으로 중단됐다.
- **원인** — `-c safe.directory=...`는 첫 번째 Git 프로세스에만 적용된다. 세미콜론 뒤의 두 번째 `git merge-tree`는 별도 프로세스인데 같은 명령 단위 설정을 지정하지 않았다.
- **해결** — 전역 Git 설정을 변경하지 않고 `merge-tree` 프로세스에도 동일한 명령 단위 safe.directory를 명시했다. merge tree `68871c5`가 생성되어 최신 develop과 충돌 없음을 확인했다.
- **예방** — 소유자가 다른 격리 복제본에서 Git 명령을 여러 segment로 실행하면 각 `git` 호출에 명령 단위 safe.directory를 독립적으로 붙인다. 전역 safe.directory 추가로 우회하지 않는다.

### GS-T044. ON_ENTER Component 효과와 Event session 병합이 Object 숨김을 되돌림 (해결)

- **증상** — v1.1 점수 목표를 추가한 뒤 수집 Runtime 회귀 테스트에서 점수는 증가했지만 아이템 Object가 다시 보이고 inventory 흐름이 깨졌다. 첫 수정에서는 숨겨진 대상을 Event가 실행해 Runtime 실패까지 발생했다.
- **원인** — `ON_ENTER` Event와 `SCORE_VALUE/PICKUP` Component가 각각 같은 `RuntimeSessionState.objectVisibility` snapshot을 변경했다. 전체 snapshot을 단순 spread하면 나중 snapshot의 변경되지 않은 `true`가 앞선 `false` 변경까지 덮어썼다.
- **해결** — Event는 접촉 전 상태에서 실행하고 Component 효과도 같은 기준 상태에서 계산한 뒤, 기준 상태와 실제로 달라진 visibility key만 순서대로 병합했다. Event의 inventory/variable/Scene 변경과 Component의 점수/피해/숨김을 모두 보존하고 전체 204 tests로 회귀 검증했다.
- **예방** — 독립 reducer 결과를 합칠 때 전체 상태 객체를 spread하지 않는다. 공통 base에 대한 field delta를 계산하거나 하나의 순차 reducer pipeline으로 통합하고, Event+Component가 같은 Object를 수정하는 결합 테스트를 유지한다.

### GS-T043. 로컬 게시 port 신규 테스트가 잘못된 factory export를 import함 (해결)

- **증상** — Local Publication unit test 3건 중 2건이 `createStarterProject is not a function`으로 실패했다.
- **원인** — 템플릿 모듈이 내부에서 starter factory를 사용한다는 이유로 해당 factory도 재수출할 것이라고 가정해 `projectTemplates.ts`에서 import했다.
- **해결** — 실제 export 소유 파일 `createStarterProject.ts`를 확인해 테스트 import를 수정했다. Local publish v1/v2 snapshot, 미게시 오류, revision 충돌 3건을 다시 통과시켰다.
- **예방** — 테스트 fixture factory는 검색으로 실제 export 경계를 확인하고, 모듈 내부 import를 public re-export로 추정하지 않는다.

### GS-T042. 로컬 브라우저 QA가 실 API 인증과 종료된 개발 서버 때문에 편집 route에 진입하지 못함 (해결)

- **증상** — 5174 편집 주소는 개발 서버 종료로 연결이 거절됐고, 서버를 다시 띄운 뒤 게스트 입장은 실제 API 미구현 때문에 실패했다. Game Studio edit route는 회원 전용이라 guest mock만으로도 접근할 수 없었다.
- **원인** — Vite 기본 실행이 `VITE_USE_MOCK=false`인 실제 API 선택 경로였고, 새 인앱 브라우저 세션에는 기존 회원 session이 없었다. 이전 화면이 열려 있었다는 사실을 인증 근거로 사용할 수 없다.
- **해결** — 프로젝트가 제공하는 공식 `VITE_USE_MOCK=true` 선택점을 사용해 별도 5175 개발 서버를 띄우고 local Google mock callback에서 테스트 회원을 만든 뒤 편집 route를 검증했다. 저장·Publish는 실행하지 않고 Canvas의 선택·복제·이동·도구 상태와 반응형 geometry만 확인했다.
- **예방** — 브라우저 QA 전에 대상 port와 auth API mode를 먼저 확인한다. 인증 저장소를 임의 검사·주입하거나 production guard를 우회하지 않고 공식 mock selector를 사용한다.

### GS-T041. 코드 복제본의 `origin`이 GitHub가 아니라 바탕화면 기준 저장소를 가리킴 (해결)

- **증상** — 새 Maker 브랜치를 `git push origin`한 출력이 GitHub URL이 아니라 `C:\Users\SSAFY\Desktop\SSAFESTA`로 나타나 기준 저장소에 branch ref가 하나 생겼다.
- **원인** — 이 격리 복제본은 `origin`을 로컬 기준 저장소, `github`를 실제 GitHub 원격으로 등록했는데 일반적인 원격 이름을 가정했다. escalated Git 실행에서는 소유자 차이로 `safe.directory` 경고도 함께 발생했다.
- **해결** — `git remote -v`를 다시 확인하고 명령 한 번에만 제한한 `safe.directory`로 `github/codex/game-studio-maker-redesign`에 정확히 push했다. 바탕화면 저장소에서는 방금 만든 branch ref만 삭제했으며 worktree·index·파일은 변경하지 않았다.
- **예방** — 격리 worktree/clone에서 push 전 `remote -v`와 branch upstream을 항상 함께 출력한다. `origin`이 GitHub라는 가정을 금지하고 실제 GitHub remote 이름을 명시한다.

### GS-T040. 수집 템플릿 기본 점수 Component와 기존 Runtime 테스트 주입이 중복됨 (해결)

- **증상** — 수집 템플릿에 기본 `SCORE_VALUE`를 넣은 뒤 전체 테스트에서 `treasure1 has duplicate component types` 계약 오류가 발생했다.
- **원인** — 기존 gameplay 테스트가 점수 동작을 확인하려고 `treasure1`에 `SCORE_VALUE`를 무조건 추가했다. 템플릿이 실제 점수 구성을 기본 제공하게 바뀐 뒤 테스트 setup이 중복 Component를 만들었다.
- **해결** — 계약의 Component type 중복 금지는 유지했다. 테스트는 점수 Component가 있으면 값을 25로 교체하고 없을 때만 추가하도록 바꿔 같은 Runtime 행동을 검증했다. 전체 37 files / 196 tests와 build·lint를 재통과했다.
- **예방** — 완성 템플릿을 테스트 fixture로 사용할 때 Component를 무조건 append하지 않고 기존 type을 조회해 replace-or-add한다. 구조적 템플릿 프로필 테스트도 함께 유지한다.

### GS-T039. 로컬 Asset port가 서버 Authoring 모드에서도 `asset://local`을 Draft에 넣을 수 있음 (해결)

- **증상** — #48·#69 Backend 검토에서 API 모드의 저장 버튼은 local Asset preflight를 거치지 않아 `asset://local`을 PUT Draft에 보낼 수 있고, 서버가 계약대로 거부하면 이후 모든 저장이 막힐 수 있음이 확인됐다. MIME 검사도 `image/*`라 SVG가 통과하고 AUDIO가 v1 port에 남아 있었다.
- **원인** — Local Preview repository와 향후 remote READY repository가 같은 port를 쓰면서도 client가 assetId를 필수 발급했고, Edit route가 서버 API 활성 여부와 무관하게 local repository를 기본 주입했다. 파일 검사도 사용자 안내 문구보다 넓었다.
- **해결** — save port를 `{kind,file,suggestedAssetId?}` 요청으로 바꿔 원격 구현이 client 제안을 무시하고 서버 ID를 반환할 수 있게 했고, Promise는 local preview 가능 또는 remote READY 이후에만 resolve한다는 계약을 고정했다. v1은 5MiB·PNG/JPEG/GIF/WebP만 허용하고 SVG·AUDIO를 거부한다. 서버 Authoring 모드에서는 #69 remote repository가 생기기 전 local repository를 주입하지 않는다.
- **예방** — Local/Remote 구현을 같은 port로 교체할 때 ID 발급 주체, READY 시점, 허용 MIME, persisted source authority, API feature flag 조합을 계약 테스트에 포함한다.

### GS-T038. 1040px PC 최소 폭 복구가 800px 앱 창의 가로 탐색을 다시 강제함 (해결)

- **증상** — 기능은 겹치지 않았지만 800px 인앱 창에서 document가 1040px로 유지되어 왼쪽 Scene과 오른쪽 속성을 오가려면 페이지 자체를 가로 이동해야 했다. 큰 맵은 세 panel 사이에서 지나치게 작아졌다.
- **원인** — 387px에서 panel이 겹친 GS-T034를 해결하면서 모든 PC 창에 1040px 최소 폭을 다시 적용했다. 모바일 수준의 폭과 작은 PC 도구 창을 같은 규칙으로 처리한 과보정이었다.
- **해결** — 지원 최소 폭은 760px, 권장은 1280px로 분리했다. 760~1039px은 170px/가변/260px compact 3열과 중앙 Canvas 내부 이동을 사용하고 document 가로 overflow를 없앴다. `화면 넓게`/Shift+F 집중 모드로 양쪽 panel을 숨겨 800px에서도 맵 전체를 편집할 수 있게 했다.
- **예방** — viewport QA는 800px compact와 1440px 권장을 모두 캡처하고 document overflow, Canvas 내부 overflow, panel hit target, 집중 모드 복구를 각각 확인한다.

### GS-T037. 첫 방문 안내가 새 프로젝트에 나타나지 않고 보조 버튼 글자가 사라짐 (해결)

- **증상** — 한 게임에서 안내를 닫은 뒤 다른 gameId에 처음 들어가도 안내가 나타나지 않았다. 안내·템플릿 확인의 보조 버튼은 흰 배경과 흰 글자가 겹쳐 빈 버튼처럼 보였다.
- **원인** — 안내 완료 key가 브라우저 전체에서 하나였고, 보조 버튼이 상위 FESTA button 색상을 상속하면서 배경 대비를 명시하지 않았다.
- **해결** — 안내 key를 `gameId`별 v3 key로 바꾸고 안내/템플릿 보조 버튼에 어두운 배경·테두리·밝은 글자를 명시했다. 새 gameId 자동 표시와 두 modal의 버튼 가독성을 실제 screenshot으로 다시 확인했다.
- **예방** — onboarding 저장 범위는 계정·기기·프로젝트 중 무엇인지 요구사항에 명시하고, modal의 primary/secondary/destructive 버튼은 상위 전역 스타일 상속에 기대지 않는다.

### GS-T036. 풍부한 Asset과 6종 템플릿이 ID 목록·이모지 카드에 숨음 (해결)

- **증상** — 50개 이상 제공 이미지와 6종 playable 템플릿이 구현돼 있었지만 Object 속성은 긴 select 한 줄이고 템플릿은 이모지와 설명뿐이라 초보자가 결과를 상상하거나 재료를 찾기 어려웠다. ID·X/Y·Component 제거가 첫 화면부터 노출됐다.
- **원인** — 데이터 계약과 범용 기능 구현을 먼저 완료하면서 Asset discoverability와 beginner disclosure를 후순위로 두었다. 기술적 가용성을 사용자 인지 가능성으로 잘못 간주했다.
- **해결** — 6종 16:9 플레이 화면을 생성·압축해 템플릿 카드에 연결하고, Object용 Asset만 캐릭터·사물/장식·내 이미지로 분류해 검색 가능한 visual picker로 제공했다. Inspector는 기본/고급으로 나눠 내부 ID·좌표·Component 조작을 기본 화면에서 숨겼다.
- **예방** — Asset 추가 완료 조건에는 개수뿐 아니라 실제 card preview, 역할 필터, 검색, 선택 적용, 잘못된 역할 제외, 초보자 용어 검증을 포함한다.

## 2026-08-23

### GS-T035. 자동화 브라우저의 백그라운드 throttling을 제품 FPS로 오인할 위험 (측정 경계 확정)

- **증상** — Runtime에 FPS 계측기를 붙였을 때 인앱 자동화 탭에서 화면이 보이는 상태에도 약 1fps가 기록됐다.
- **원인** — 앱 내부 브라우저·자동화 환경의 frame scheduling과 background throttling이 실제 사용자의 활성 데스크톱 탭과 달랐다. 이 값으로 Runtime 성능을 판정하면 제품 코드와 측정 환경을 혼동한다.
- **해결** — 일반 사용자 화면에서는 FPS 계측기를 숨기고 `?perf=1`에서만 표시하도록 바꿨다. 자동 테스트는 500 Object 편집 command의 100ms gate를 담당하고, 55~60fps 합격 여부는 외부 브라우저 활성 탭·동일 fixture·측정 장비를 기록하는 사람 QA로 분리했다.
- **예방** — FPS 결과에는 브라우저, 해상도, 장치, visibility, foreground 여부를 함께 남긴다. throttled 자동화 탭 수치는 회귀 단서로만 사용하고 release gate로 사용하지 않는다.

### GS-T034. 앱의 매우 좁은 viewport에서 반응형 3열 panel이 canvas 위를 덮음 (당시 해결, GS-T038에서 최종 개선)

- **증상** — 387px 인앱 viewport에서 처음에는 document overflow가 없었지만 Scene/Object panel과 우측 속성 panel이 중앙 canvas와 겹쳐 layer·zoom 버튼 클릭을 가로챘다.
- **원인** — GS-T028에서 1040px 최소 폭을 제거하며 3열을 매우 좁은 폭 안에 강제로 축소했다. Game Studio는 PC 편집기인데 모바일 폭까지 모든 panel을 동시에 끼워 넣으려 해 hit target과 작업영역을 잃었다.
- **해결** — PC 편집기의 최소 작업 폭 1040px을 다시 계약으로 명시하고, 좁은 내장 창에서는 document 가로 탐색으로 전체 workspace를 보존하도록 고쳤다. 우측 panel이 canvas 좌표를 덮지 않게 한 뒤 layer 잠금/해제와 새로고침 보존을 실제 클릭으로 다시 검증했다.
- **예방** — PC 제작 도구는 ‘모바일처럼 축소’와 ‘작업영역을 보존하며 탐색’ 중 하나를 명시적으로 선택한다. 배치 QA는 보이는 모양뿐 아니라 `elementFromPoint`, panel rect, 실제 클릭 성공 여부를 확인한다.

### GS-T033. Published loader가 빈 Asset 배열을 새 값으로 받아 무한 렌더링함 (해결)

- **증상** — Published API 오류 화면은 보이지만 console에 `Maximum update depth exceeded`가 반복됐고 재시도 전에도 render가 계속 발생했다.
- **원인** — `project?.assets ?? []`가 render마다 새 빈 배열을 만들고 Asset URL hook이 매번 새 state object를 저장해 effect 의존성이 끝없이 바뀌었다.
- **해결** — module-level 안정 빈 배열을 사용하고, URL map이 실제로 바뀌지 않으면 state 갱신을 생략하는 equality guard를 추가했다. Local/Published 양쪽 surface에 적용하고 브라우저 새로고침 후 console 오류가 사라졌음을 확인했다.
- **예방** — React effect 의존성에 fallback array/object literal을 직접 넣지 않는다. 비동기 resolver hook은 입력 안정성뿐 아니라 동일 결과에서 state update를 생략하는 방어를 갖춘다.

### GS-T032. 최신 develop 정본화 병합 뒤 PR #53 공통 문서가 다시 충돌함 (해결)

- **증상** — PR #53이 `CLEAN`에서 `DIRTY/CONFLICTING`으로 바뀌고 rebase 중 `docs/26_팀_결정_필요사항.md`, `specs/README.md` 두 파일에서 충돌했다.
- **원인** — 최신 develop PR #67이 오류 봉투·spec 상태 정본·부스 계약을 공통 문서에 추가했고, Game Studio 최초 문서 커밋도 같은 표의 인접 행을 추가했다. 코드나 spec 019 내부 계약 충돌은 아니었다.
- **해결** — rebase 전 backup branch를 만들고 최신 develop의 #58·#59·#62 정책과 폐기된 spec 018 행을 보존했다. Game Studio P2 행과 #33 공개 정책·#34 Portal ID 정책만 행 단위로 병합했다. 이어 Backend 리뷰의 5필드 오류 봉투와 Published 캐시 정정을 적용하고 계약 fixture를 재검증했다.
- **예방** — 공통 인덱스·팀 결정 표는 기능 브랜치 장기 보유를 피하고, 최종 push 직전에 최신 develop을 받아 행 단위로 병합한다. `ours/theirs` 파일 전체 선택 뒤 필요한 상대 행을 명시적으로 복원해 정본 정책 유실을 막는다.


### GS-T031. 최종 fetch 중 원격 추적 ref가 먼저 갱신되어 lock 비교가 실패함 (해결)

- **증상** — 코드 복제본의 최종 `fetch`가 `github/front`와 `github/game`에 대해 현재 ref가 예상값보다 이미 앞서 있다는 `cannot lock ref ... is at ... but expected ...` 오류로 종료됐다.
- **원인** — fetch가 원격 값을 읽은 뒤 ref를 기록하기 전에 같은 복제본의 원격 추적 ref가 다른 갱신 동작으로 최신 값으로 이동했다. 남은 lock 파일이나 손상된 객체가 아니라 compare-and-swap 보호가 중복 갱신을 거부한 상태였다.
- **해결** — lock 파일을 임의 삭제하지 않고 실제 `github/front` ref와 branch ahead/behind를 다시 읽었다. 최신 ref `b92593e`가 정상임을 확인하고, Game Studio 단일 커밋을 그 위로 충돌 없이 rebase한 뒤 전체 98 tests·build·lint를 재실행하고 새 원격 브랜치로 push했다.
- **예방** — fetch lock 오류가 나면 먼저 ref가 이미 목표 값으로 전진했는지 확인한다. 손상으로 단정해 lock/ref를 삭제하지 말고 `status`, `rev-list`, 변경 경로 대조 후 필요한 rebase와 전체 검증을 수행한다.

### GS-T030. root `.gitignore`의 `Reference/` 규칙이 Web Runtime 디렉터리까지 숨김 (해결)

- **증상** — 테스트와 build는 `runtime/reference`를 정상 사용했지만 일반 `git add src/game-studio` 뒤 staged 목록에 핵심 Runtime 3파일이 없었다.
- **원인** — Unity용으로 보이는 root `.gitignore`의 `Reference/` 규칙이 Windows 대소문자 비구분 경로에서 Game Studio의 `runtime/reference/`에도 적용됐다.
- **해결** — `git check-ignore -v`로 정확한 규칙을 확인하고 Game Studio 전용 Runtime 3파일만 `git add -f`로 최초 추적했다. root ignore나 Unity 경로는 변경하지 않았다. 추적된 뒤에는 후속 수정이 일반 Git 상태에 표시된다.
- **예방** — 커밋 전 `git status --ignored <feature-root>`와 staged 파일 목록을 함께 확인한다. 새 디렉터리 이름이 범용 ignore pattern과 겹치면 최초 추적 여부를 명시적으로 검증한다.

### GS-T029. Action Component 검증 분기가 Scene validator에 들어가 template build가 실패함 (해결)

- **증상** — Vitest에서 6종 template과 SHOOTER Component가 `project.scenes[0].type is invalid`로 거부되고 TypeScript build도 새 test의 `unknown` reduce 추론으로 실패했다.
- **원인** — `DAMAGE`부터 `SPAWNER`까지 Component 분기를 `validateComponentShape`가 아니라 인접한 `validateSceneShape`에 추가했고, generic `Array.reduce`가 Runtime state 초기값을 `unknown`으로 추론했다.
- **해결** — Component 분기를 올바른 validator로 이동하고 test frame 진행을 명시적 typed loop로 바꿨다. 이후 Vitest 85개와 production build를 함께 통과시켰다.
- **예방** — discriminated union을 확장할 때 각 신규 variant마다 유효 1건/오류 1건을 먼저 추가하고 test 통과뿐 아니라 `tsc -b`를 같은 검증 묶음으로 실행한다.

### GS-T028. 1040px 최소 폭 때문에 인앱 브라우저에서 왼쪽 제작 패널이 화면 밖으로 밀림 (당시 해결, GS-T034에서 PC 기준 재조정)

- **증상** — 813px 인앱 브라우저에서 root 1040px, document scrollWidth 1040px, scrollX 237로 측정됐고 왼쪽 Scene/Object 패널과 상단 제목이 동시에 보이지 않았다.
- **원인** — 데스크톱 3열 배치를 보호하려고 root에 `min-width: 1040px`을 고정했고 Map stage 최소 폭까지 document overflow에 합쳐졌다.
- **해결** — 1039px 이하에서 root 최소 폭을 제거하고 170px/가변/260px 3열로 재배치했다. Map stage의 큰 폭은 중앙 canvas scroll이 소유하게 하고 palette를 한 열로 압축했다. 재측정은 root/scrollWidth 모두 813px, scrollX 0이다.
- **예방** — 편집기 QA는 넓은 데스크톱뿐 아니라 앱 패널 폭에서 document overflow와 내부 workspace overflow를 구분해 수치로 확인한다.

### GS-T027. Atlas frame·Object zIndex가 잘못되어 NPC/대화 인물 위에 다른 Object가 보임 (해결)

- **증상** — NPC가 말풍선 frame처럼 보이거나 Overlay 대화 인물의 얼굴 위에 문 Sprite가 표시됐다.
- **원인** — 생성 atlas의 실제 row/frame 위치를 눈으로 확인하지 않고 catalog index를 추정했고, Runtime Object inline zIndex가 Dialogue layer 기본 zIndex보다 높았다.
- **해결** — 원본 atlas를 직접 열어 frame mapping을 고정하고 기존 4×4 atlas의 검증된 NPC/key/door frame을 재사용했다. Dialogue layer는 World Object 전체보다 높은 stacking context로 올린 뒤 브라우저 screenshot으로 재검증했다.
- **예방** — 생성 atlas는 metadata만 믿지 않고 각 frame을 실제 렌더해 확인하며, World/Effect/Dialogue/HUD의 zIndex 대역을 겹치지 않게 예약한다.

### GS-T026. `useSyncExternalStore` snapshot 객체가 매 호출마다 새로 생성되어 Editor가 무한 갱신됨 (해결)

- **증상** — Authoring store를 React shell에 연결한 직후 `getSnapshot should be cached` 경고와 maximum update depth 오류가 발생했다.
- **원인** — `getState()`가 내용이 같아도 매 호출 새 wrapper 객체를 반환해 React가 외부 store 상태가 계속 바뀐 것으로 판단했다.
- **해결** — Store 내부에 현재 snapshot 객체를 캐시하고 실제 command/undo/redo/reset에서만 새 snapshot을 만들도록 바꿨다. store unit test와 브라우저 편집/저장 여정으로 재검증했다.
- **예방** — `useSyncExternalStore` adapter는 동일 상태에서 `Object.is(getSnapshot(), getSnapshot())`가 참이 되도록 구현하고 이 항등성을 unit test로 고정한다.

## 2026-08-21

### GS-T025. Booth Studio 병합 뒤 PR #47의 공통 router가 충돌함 (해결)

- **증상** — PR #47은 처음에는 병합 가능했지만 최신 `front`에 spec 005 Booth Studio MVP가 합쳐진 뒤 `DIRTY/CONFLICTING`으로 바뀌었다.
- **원인** — Booth Studio와 Game Studio가 같은 `src/app/router/index.tsx` 배열 끝에 각각 새 route를 추가했고, PR #47의 기준선이 최신 `front`보다 뒤처졌다.
- **해결** — 기존 PR tip을 backup branch로 보존하고 Game Studio 4개 커밋을 최신 `front` 위로 rebase했다. 충돌 구간에서는 Booth Studio route와 Game Studio edit/play lazy route를 모두 유지했다. 테스트 29개·build·lint 통과 후 `--force-with-lease`로 PR branch를 갱신해 `CLEAN/MERGEABLE`을 회복했다.
- **예방** — 파트 공통 router를 건드리는 PR은 리뷰 대기 중에도 base branch가 갱신되면 mergeability를 다시 확인한다. 충돌 해결 때 어느 한쪽 route 배열을 통째로 선택하지 않고 양쪽 공개 경로를 함께 검증한다.

### GS-T024. Foundation 브랜치와 develop의 019/020 번호 결정이 반대로 진행됨 (해결)

- **증상** — Foundation 브랜치는 Game Studio를 020으로 옮겼지만 최신 develop에는 `020-erd-schema`가 이미 존재하고 019가 Game Studio용으로 비어 있어 동일 번호 디렉터리가 둘 생겼다.
- **원인** — 두 브랜치가 같은 최초 충돌을 서로 반대 방향으로 해소했고 Foundation이 develop의 후속 ERD 개명을 반영하지 못했다.
- **해결** — 최신 develop의 `specs/README.md`와 실제 tree를 정본으로 삼아 Game Studio를 `019-game-studio`로 복귀하고 모든 현재 계약·상위 문서·KHS 범위를 동기화했다. 역사적 기록은 선제 020 이동이 당시 수행됐음을 남겼다.
- **예방** — 번호 충돌 해결은 별도 feature에서 단독 확정하지 않고 develop의 번호 인덱스와 실제 디렉터리를 함께 확인한 뒤 최종 PR에서 한 번만 고정한다.

### GS-T023. 파트별 SDD 규칙이 합쳐진 develop 위로 18개 문서 커밋을 재배치하며 충돌함 (해결)

- **증상** — Game Studio Foundation을 최신 develop 위로 rebase할 때 Backend API, 팀 결정, specs index, agent 진입 문서에서 여러 차례 충돌했다.
- **원인** — Foundation 작성 뒤 develop에 Backend 문서 import와 PR #44 파트별 산출물 규칙이 추가됐고 같은 공유 문서의 인접 구간을 양쪽이 수정했다.
- **해결** — 원 tip을 backup branch로 보존하고 새 `codex/game-studio-docs-sync`에서 rebase했다. develop의 공통/agent 문서를 우선 보존하고 Game Studio 행만 수동 결합한 뒤 실행 문서 5종을 `FE/`와 `BE/`로 분리했다.
- **예방** — 다중 파트 spec은 처음부터 root `spec.md/contracts`와 part execution artifacts를 나누고, 오래 열린 문서 브랜치는 PR 직전 최신 develop 위에서 별도 sync branch로 검증한다.

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

### GS-T016. Game Studio와 Backend ERD spec 번호가 019로 충돌함 (당시 해결, GS-T024에서 최종 정리)

- **증상** — #21 답변에서 `specs/019-game-studio`와 `origin/back`의 `specs/019-erd-schema`가 같은 번호를 사용 중인 것이 확인됐다.
- **원인** — Backend spec이 `specs/README.md`에 등록되지 않아 Game Studio 생성 시 전체 원격 브랜치의 경로까지 보이지 않았다.
- **해결** — 당시 모든 원격 브랜치의 spec 019~022 경로를 검색하고 비어 있던 020으로 Game Studio를 선제 이동했다. 이후 develop이 ERD를 020으로 옮긴 결정과 합쳐지며 GS-T024에서 Game Studio를 019로 최종 정리했다.
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

- **증상** — 당시 기능 브랜치의 Game Studio spec 경로와 `docs/KHS/27`, `28` 문서를 현재 경로에서 읽으려 했으나 파일이 없다는 오류가 발생했다.
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
