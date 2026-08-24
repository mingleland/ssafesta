# FESTA Game Studio 작업일지

> **범위**: `specs/019-game-studio`, `festa-frontend/src/game-studio/`, GameProject 계약, Web 2D Runtime,
> Spring Draft/Publish·Portal Binding, FESTA Host 연동.
>
> Unity 월드·Booth Runtime 일반 작업은 이 문서에 기록하지 않는다.
> Game Studio 작업과 문제는 각각 `27_Game_Studio_작업일지.md`,
> `28_Game_Studio_트러블슈팅.md`에만 기록한다.

## 2026-08-24

### GitLab 이관 전 Game Studio 인수인계 정리 ✅

- 🤖 실제 GitLab Project 생성·Import·remote 추가·push는 하지 않고, 현재 GitHub `develop`·`front`, Game Studio stacked PR #72·#79·#80, 문서 PR #53의 base/head/SHA와 OPEN Issue #48·#55·#56·#69·#73·#78·#81 담당을 `29_Game_Studio_GitLab_이관_준비.md`에 고정했다.
- 🤖 GitHub 공식 원격은 로컬 alias `github`이고 `origin`은 `C:\Users\SSAFY\Desktop\SSAFESTA` 로컬 저장소임을 확인했다. 현재 worktree에서 `git push --mirror`를 실행하지 않고 GitHub Import 또는 fresh clone만 허용하도록 가드했다.
- 🤖 원격 실측은 branch 23개, tag `v0.0.1-poc` 1개, tracked file 1,867개, pack 약 630.46 MiB, 최대 blob 약 11.88 MiB, submodule/LFS pointer 없음, connectivity fsck 성공이다. 저장소에 `.gitlab-ci.yml`·`Jenkinsfile`·GitHub Workflow가 없어 Jenkins Webhook과 credential은 별도 Infra 인수인계가 필요함을 기록했다.
- 🤖 GitLab 공식 Import·Repository Mirroring·Protected Branch·CI/CD Variable 문서를 근거로 PR→MR 참조 차이, status check 수동 복원, attachment/user mapping, branch protection·Secret 검증 체크리스트를 작성했다. 제품 계약과 Backend·Unity·AI 코드는 변경하지 않았다.
- 🤖 이관 문서만 추가하고 끝내지 않고 FE plan·maker reference·Event Runtime·part boundary·contract index의 오래된 “#78 이후 구현” 표현을 현재 상태인 “v1.1 FE candidate 구현, BE/AI 승인 대기”로 통일했다. FE tasks는 85개 중 77개 완료, 8개 잔여로 재계산했다.
- 🤖 이관 기준 코드 브랜치에서 Frontend 38 files/204 tests, production build, lint를 다시 통과했고 문서 브랜치에서는 계약 fixture 7/7, Runtime trace 6/6, schema JSON parse, 상대 링크와 staged diff 검사를 통과했다.
- 🤖 최신 `github/develop`은 `5e39013`이고 문서 브랜치는 behind 0이었다. PR #53·#72·#79·#80은 모두 OPEN/CLEAN/MERGEABLE 상태를 확인했다.
- 트러블슈팅: GS-T046~GS-T047

### 로컬 게시 전체 흐름·GameProject v1.1 목표 규칙 ✅

- 🤖 기존 Maker PR과 분리한 코드 브랜치 `codex/game-studio-local-publish-loop`에서 Backend·Unity 변경 없이 후속 작업을 진행했다. `VITE_USE_MOCK=true`일 때 브라우저 Draft를 불변 Published snapshot으로 복제하고 version을 올리는 Publisher/Published Repository를 추가했다. 운영 API 모드는 기존 서버 adapter를 그대로 사용한다.
- 🤖 GameProject v1.1 FE candidate에 `SCORE_AT_LEAST`, `DEFEAT_ENEMIES`, `SURVIVE_SECONDS`, `ALL/ANY`, `RESPAWN/END_GAME` 규칙을 추가했다. v1.0은 계속 읽고 편집 시 기본 규칙과 함께 v1.1로 승격한다. 120ms 결정적 tick으로 생존 시간을 계산하고, 점수·처치 수·시간 진행도를 Runtime HUD에 표시한다.
- 🤖 SHOOTER는 적 3명 처치, SURVIVAL은 30초 생존과 체력 0 도전 실패를 기본 목표로 연결했다. 데이터 탭에서 초보자가 목표를 추가·삭제·수정하고 여러 목표 방식과 체력 0 결과를 한국어로 고를 수 있게 했다.
- 🤖 Publish preflight에 완료 경로 검사를 추가해 목표와 `COMPLETE_GAME`이 모두 없는 끝낼 수 없는 프로젝트를 게시하지 않는다. 게시 성공 뒤 `게시본 확인 vN`으로 일반 `/app/games/{gameId}/play`를 즉시 연다.
- 🤖 자동 검증은 Frontend **38 files / 204 tests**, production build, lint를 통과했다. 브라우저에서 game 904로 `슈팅 템플릿 → 적 3명 목표 → 저장 → 게시 v1 → /play → 동일 목표 HUD`를 확인했고 viewport 1280에서 document overflow 없이 Published Runtime이 mount됐다.
- 🤖 v1.1 FE candidate는 Issue #78의 Backend validator와 AI 허용 출력 합의 전까지 Mock/Frontend 경계다. 실제 다중 사용자 공유·stable 사용자 Asset·Portal·Coin은 #48·#55·#56·#69 및 후속 세션 계약이 남아 있다.
- 🤖 기존 OPEN/CLOSED 이슈에서 Published Coin 세션 계약이 추적되지 않음을 확인하고 [#81](https://github.com/kanghyunsoon/ssafesta/issues/81)을 생성해 @strdeok·@ghkim1632·@colosair를 지정했다. 가격·차감·idempotency는 GameProject가 아니라 서버 Game/session metadata가 소유하고, Unity는 웹 게임 진입 요청만 담당하도록 경계를 고정했다.
- 🤖 문서 PR #53은 GitHub API가 `UNKNOWN`을 반환했지만 최신 `develop`과 `git merge-tree --write-tree`를 다시 실행해 충돌 없이 merge tree가 생성됨을 확인했다.
- 트러블슈팅: GS-T043~GS-T045

### Maker형 자유 편집 Canvas·실제 템플릿 재설계 ✅

- 🤖 Game Studio 관련 OPEN 이슈 #48·#55·#56·#69·#73을 다시 확인했다. 새 답변은 없고 PR #53·#72는 `CLEAN/MERGEABLE` 상태라, Backend·Unity 답변과 무관한 제작 UX를 새 코드 브랜치 `codex/game-studio-maker-redesign`에서 진행했다.
- 🤖 Canvas에 단일/Shift·Ctrl 추가/영역 드래그 선택, 선택 묶음 경계 보존 이동, `Q` 선택·`W` 화면 이동·`G` 격자, Ctrl+D 복제, Delete 안전 삭제, 선택 수 안내를 추가했다. 선택하지 않은 Object의 카드 배경과 ID label을 숨겨 플레이 화면을 가리던 문제도 줄였다.
- 🤖 복제 command는 새 Object/Event ID를 발급하고 선택 Object가 Trigger인 Event 및 묶음 내부 SHOW/HIDE 참조를 재연결한다. 일괄 삭제는 Player Spawn과 선택 밖 Event 참조 Object를 보존하며, 자기 자신을 숨기는 pickup Event는 Object와 함께 제거한다.
- 🤖 Scene Inspector에서 World map 크기를 수정할 수 있게 했고 Tile row-major 데이터를 좌상단 기준으로 보존하며 Object를 새 경계 안으로 이동한다. 모든 변경은 기존 GameProject 1.0.0과 undo/redo store를 그대로 사용한다.
- 🤖 STORY는 다중 Dialogue·선택·변수 기반 약속 이야기, ESCAPE는 두 World Scene·단서·열쇠·스위치·숨은 출구 흐름으로 재작성했다. COLLECTION에는 배치 장애물과 점수를, PLATFORMER/SHOOTER/SURVIVAL에는 서로 다른 지형·적·포탑·Spawner·Checkpoint 구성을 넣었다. 6종의 Scene/Object/Event/Dialogue 구조 프로필이 모두 다름을 테스트로 고정했다.
- 🤖 자동 검증은 Frontend **37 files / 196 tests**, production build, lint를 통과했다. 인앱 브라우저에서는 `2개 선택 → Event 포함 복제 → 묶음 이동`, 화면 이동/격자 상태, 1024px 전체 도구 노출, 800px document 무가로 overflow를 확인했고 console error는 0건이었다.
- 🤖 구현 커밋 `3f695b5 feat(game-studio): add maker-style canvas editing`을 실제 GitHub 원격 `codex/game-studio-maker-redesign`에 게시하고, PR #72 위의 독립 stacked [PR #79](https://github.com/kanghyunsoon/ssafesta/pull/79)를 열었다. #79는 `CLEAN/MERGEABLE`이며 #72 병합 뒤 `front`로 retarget한다. 잘못 등록된 로컬 `origin`으로 생긴 바탕화면 저장소 branch ref는 작업 파일을 건드리지 않고 즉시 제거했다.
- 🤖 사람 실사용 검증은 기존 [#73](https://github.com/kanghyunsoon/ssafesta/issues/73)에 결과와 잔여 항목을 추가했다. v1.0으로 표현할 수 없는 적 처치 수·생존 시간·점수 임계 승리 규칙은 FE·BE·AI 공동 [#78](https://github.com/kanghyunsoon/ssafesta/issues/78)을 만들고 @strdeok·@ghkim1632·@colosair를 지정했다.
- 트러블슈팅: GS-T040~GS-T042

### 메이플스토리 월드 메이커 기준 초보 제작 UX 고도화 ✅

- 🤖 메이플스토리 월드 Creator Center의 Scene 중심 작업, Preset, 즉시 테스트, 단축키, 학습 난이도 구조를 확인했다. FESTA에는 임의 Script/API를 노출하지 않고 `배치 → 모습 선택 → 빠른 행동 → 플레이`를 기본으로 적용했다.
- 🤖 이야기·방탈출·수집·점프맵·슈팅·생존 6종에 16:9 실제 플레이 화면 WebP 미리보기를 추가하고 장르, 이동 방식, 난이도, 예상 수정 시간, 핵심 시스템을 한 카드에서 비교하게 했다. 이모지 설명 카드와 선택 확인 버튼 대비 문제를 제거했다.
- 🤖 Object Inspector의 50개 이상 Asset 단일 선택 목록을 캐릭터·사물/장식·내 이미지 분류, 한국어 검색, 실제 frame/animation 미리보기가 있는 재료함으로 교체했다. 초상·배경·Tileset은 Object 후보에서 제외하고 선택한 Object만 사용자 이미지로 교체한다.
- 🤖 기본 Inspector는 모습·크기·가시성·현재 동작만 보여주고 ID·좌표·zIndex·Component 추가/삭제·Object 삭제를 고급 설정으로 분리했다. 처음부터 내부 데이터 용어를 이해해야 하던 진입 장벽을 낮췄다.
- 🤖 최초 안내 localStorage를 프로젝트별 key로 바꿔 새 게임마다 안내가 나타나게 했고, 열쇠/문/NPC 전용 6단계를 모든 TOP_DOWN·PLATFORMER 템플릿에서 통과 가능한 5단계 범용 튜토리얼로 교체했다. 실제 플랫폼 템플릿에서 4단계 Event까지 자동 찾기·진행을 검증했다.
- 🤖 PC 최소 폭을 760px compact 기준으로 조정해 800px 창에서 문서 전체 가로 스크롤을 제거했다. `화면 넓게`/Shift+F 집중 모드가 양쪽 panel을 숨겨 큰 맵을 전체 폭으로 보여주며 Esc 또는 버튼으로 복구한다.
- 🤖 #48·#69의 Backend 최신 답변도 확인했다. 로컬 Asset은 5MiB·PNG/JPEG/GIF/WebP allow-list로 좁히고 SVG·AUDIO를 거부했다. Asset repository는 서버 발급 ID와 READY 이후 반환을 지원하도록 요청 객체형 port로 바꿨으며, 원격 Asset repository가 없는 서버 Authoring 모드에서는 local repository를 주입하지 않아 `asset://local` Draft 전송을 막았다.
- 🤖 실사용 브라우저에서 새 게임 안내, 시각 템플릿, 재료 검색 `문` 2건, 이미지 적용, 간단/고급 전환, PLATFORMER 범용 튜토리얼, 800px compact·집중 모드를 확인했다. 최종 검증은 Frontend **37 files / 191 tests**, production build, lint 모두 통과했다.
- 🤖 초보 제작 UX 구현은 최신 `front`(`5022335`) 위 코드 커밋 `7786ed6`로 고정했으며 기존 `front` 대상 [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)에 이어서 반영한다.
- 🤖 변경은 기존 `codex/game-studio-published-runtime-shell`의 `festa-frontend/src/game-studio/**`와 별도 문서 브랜치의 spec/KHS 기록에만 한정했다. Backend·Unity·공유 `game` 작업트리는 수정하지 않았다. 운영 잔여는 기존 #48·#55·#56·#69와 사람 QA #73으로 충분해 중복 이슈를 만들지 않았다.
- 트러블슈팅: GS-T036~GS-T039

## 2026-08-23

### 실사용 Authoring·운영 연결 완성도 보강 ✅

- 🤖 `front`에서 분리한 `codex/game-studio-published-runtime-shell` 브랜치에 Draft 조회/저장·revision 충돌·Publish·Published 조회·Portal resolver용 strict adapter를 구현했다. 기본값은 API 비활성이라 기존 FESTA/Booth Studio 동작에 영향을 주지 않고, `VITE_GAME_STUDIO_API_ENABLED=true` 한 곳만 바꾸면 Backend 계약에 연결된다.
- 🤖 Backend가 없어도 최초 방문 6단계 `열쇠 → 문 → 조건 → NPC → 대화 → 플레이` 가이드, 화면 요소 자동 찾기, 로컬 저장·Preview, 레이어 검색/숨김/잠금/z-order, 화살표 1칸 이동, Ctrl+S/Z/Y, Alt+L을 사용할 수 있게 했다. 잠금·숨김 상태는 GameProject와 분리된 사용자별 Editor 상태로 보존한다.
- 🤖 Publish 전 `asset://local`·`blob:` 등 로컬 전용 Asset을 막고 실제 사용 위치(Scene 배경, Tile, Object, Dialogue 인물, Component)를 함께 표시한다. Draft 409에서는 서버 최신본을 즉시 덮지 않고 로컬 JSON 백업과 서버본 다시 불러오기를 선택하게 해 사용자의 변경을 보존한다.
- 🤖 500 Object Scene에서 실제 immutable store 갱신을 반복하는 자동 성능 회귀 테스트를 추가해 최악값 100ms 미만을 gate로 고정했다. Scene 전환 뒤 체력·인벤토리·변수·숨김 Object·spawn 상태를 유지하는 Runtime E2E도 추가했다.
- 🤖 실제 인앱 브라우저에서 최초 guide 자동 표시, 튜토리얼 요소 찾기, 레이어 잠금 새로고침 보존, 로컬 Play 왕복, Published API 부재 오류/재시도/나가기를 확인했다. 불안정한 빈 Asset 배열로 Published loader가 반복 렌더링되던 문제와 좁은 앱 폭에서 우측 panel이 canvas를 가리던 문제도 실제 console·geometry 측정으로 수정했다.
- 🤖 최종 자동 검증은 Frontend **35 files / 183 tests**, production build, lint, 계약 fixture **7/7**, Runtime trace **6/6**을 통과했다. 지원하지 않는 schema, 손상된 Published project, Runtime crash 복구 화면도 별도 회귀 테스트로 고정했고 Edit/Play/Overlay는 FESTA entry와 분리된 lazy chunk로 출력된다.
- 🤖 사람 대상 20분 첫 사용 테스트와 활성 PC 탭 55~60fps 측정은 자동화 결과로 가장하지 않고 `FE/usability-test.md`의 5명 기록표와 [#73](https://github.com/kanghyunsoon/ssafesta/issues/73)으로 분리했다. @ghkim1632·@colosair를 지정했으며 앱 내부 백그라운드 탭의 1fps throttling 값은 제품 성능 판정에서 제외한다.
- 🤖 구현 커밋 `dfe5f71`을 최신 `front`(`bf45193`) 위에 재정렬해 원격 브랜치 `codex/game-studio-published-runtime-shell`로 게시하고, `front` 대상 [PR #72](https://github.com/kanghyunsoon/ssafesta/pull/72)을 생성했다. 변경은 `festa-frontend/.env.example`, Overlay Host, `festa-frontend/src/game-studio/**`에 한정하며 Backend·Unity 경로 변경은 0건이다.
- 트러블슈팅: GS-T033~GS-T035

### 구현 PR 병합·완료 이슈 정리 ✅

- 🤖 [PR #63](https://github.com/kanghyunsoon/ssafesta/pull/63)이 @colosair의 최신 `front` 기준 독립 검증 후 병합됐다. trial merge 무충돌, 전체 **19 files / 98 tests**, TypeScript build, lint, lazy Asset chunk 격리가 재확인됐다.
- 🤖 로컬 Authoring Workspace·Preview 완료 범위인 [#49](https://github.com/kanghyunsoon/ssafesta/issues/49)와 renderer·same-origin route Preview·builtin/local Asset resolver 결정 범위인 [#35](https://github.com/kanghyunsoon/ssafesta/issues/35)를 완료 근거와 함께 닫았다.
- 🤖 운영 기능은 [#48](https://github.com/kanghyunsoon/ssafesta/issues/48) Draft/Publish API, [#55](https://github.com/kanghyunsoon/ssafesta/issues/55) Published loader·오류 격리, [#56](https://github.com/kanghyunsoon/ssafesta/issues/56) GAME_PORTAL Binding으로 분리해 계속 OPEN 유지했다.
- 🤖 내장 Asset을 로컬에서 교체하는 기능과 달리 Published 게임에서 사용자 이미지를 공유하려면 서버 stable `asset://` 승격 파이프라인이 필요함을 확인했다. #48의 allow-list 검증·#55의 Runtime resolver와 겹치지 않는 업로드/가공/보존 공백을 [#69](https://github.com/kanghyunsoon/ssafesta/issues/69)로 등록하고 @strdeok · @ghkim1632 · @colosair를 지정했다.

### Backend 리뷰·최신 develop 계약 동기화 ✅

- 🤖 @strdeok의 PR #53 검토를 반영해 `GAME_REVISION_CONFLICT` 예시를 FESTA 단일 5필드 오류 봉투(`code/message/requestId/errors/warnings`)로 고쳤고, 존재하지 않는 `details` 필드를 제거했다.
- 🤖 GameProject 상한과 내부 참조 무결성을 Publish뿐 아니라 Draft 저장에도 동일 적용하도록 확정했다. `asset://local`·binary/base64·`data:`·`blob:`·`file:`은 거부하고 `builtin://`·서버 stable `asset://`만 허용한다.
- 🤖 공개 포인터 URL `/games/{gameId}/published`는 재공개 즉시 최신 version을 보도록 `Cache-Control: no-cache` + ETag 재검증으로 고쳤다. 긴 immutable cache는 후속 version 고정 URL에만 적용한다.
- 🤖 최신 `develop`(`32737ca`) 위로 문서 브랜치를 재정렬하고 `docs/26`, `specs/README` 충돌에서 최신 전역 정책과 Game Studio #33·#34 결정을 모두 보존했다.
- 트러블슈팅: GS-T032


### 범용 Authoring Tool·Reference Runtime 수직 구현 ✅

- 🤖 최신 `front` 기반 전용 `codex/game-studio-authoring-shell` 브랜치에서 Scene/Object/Tile/Properties/Event/Dialogue/Data가 한 화면에서 이어지는 한국어 Game Studio를 구현했다. 공유 Unity 작업트리, `festa-unity/**`, `backend/**`는 수정하지 않았다.
- 🤖 `TOP_DOWN`, `PLATFORMER`, `DIALOGUE`를 GameProject v1 하나로 검증하고, 장르는 Backend 문서 유형이 아니라 6종 시작 템플릿(스토리·방탈출·수집·점프맵·슈팅·생존)으로 분리했다.
- 🤖 4방향 캐릭터 애니메이션, 도서관/플랫폼 타일셋, 50종 이상 Object·인물 표정·배경 reference catalog를 추가했다. 기본 재료를 먼저 보여주고 선택한 Sprite에서만 `내 이미지로 교체`를 열며, 로컬 binary는 IndexedDB Asset repository가 소유한다.
- 🤖 내장 원본 PNG 8종을 화면 품질 확인 뒤 WebP로 전환해 번들 Asset을 15,440,090 bytes에서 3,587,104 bytes로 **76.8% 감소**시켰다. 저장소의 중복 PNG는 제거했고 원본 생성 결과는 Codex generated-images 기록에서 복구할 수 있다.
- 🤖 불변 Authoring command, undo/redo, Scene 생성·삭제, Tile painting, drag/place, typed Component inspector, Trigger/Condition/Action editor, 빠른 행동 recipe, Dialogue 배경·인물·표정·대사·선택지 live preview를 구현했다.
- 🤖 Reference Runtime에 이동·충돌·인벤토리·문·대화·Scene 전환 외에도 중력·점프·체력·피해 회복 시간·점수·체크포인트·자동 이동·투사체·bounded spawner를 연결했다. Preview는 Unity/WebGL 없이 same-origin `/play?source=local` route에서 같은 validator/state/event core를 실행한다.
- 🤖 DB 보호 경계는 GameProject JSON 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300으로 고정했다. 이미지·오디오 binary는 JSON/DB 대상이 아니다.
- 🤖 최초 구현 검증은 Vitest **16 files / 85 tests**였고, 최신 `front` 재정렬 뒤 전체 Frontend 기준 **19 files / 98 tests**, TypeScript production build와 lint를 다시 통과했다. Edit/Play가 별도 lazy chunk로 출력되는 것도 유지됐다.

### 인앱 브라우저 실제 사용자 여정 검증 ✅

- 🤖 813px 폭에서 전체 페이지 가로 overflow가 1040px로 밀리던 문제를 수정해 `scrollWidth=813`, `scrollX=0`을 확인했다. 왼쪽 제작 재료·가운데 맵·오른쪽 속성을 유지하고 큰 맵만 작업영역 내부에서 이동한다.
- 🤖 `슬라임 블래스터 선택 → 교체 확인 → 로컬 저장 → 플레이 → F 발사` 흐름과 상태 HUD를 실제 브라우저에서 검증했다. 템플릿은 시스템 confirm 대신 선택→명시적 불러오기 2단계로 교체한다.
- 🤖 `탐색 맵 이동 → NPC 상호작용 → 인물 Overlay 대화 → 선택지 → 같은 맵 복귀`를 실제로 완료했다. 대화 중 월드 입력이 차단되고 종료 뒤 체력·맵·인벤토리가 유지된다.
- 🤖 배경/인물 선택 목록은 역할에 맞는 builtin만 노출하고 사용자 Asset은 선택 후보로 유지했다. Dialogue layer가 높은 zIndex Object 아래로 들어가던 문제도 수정했다.
- 관련 기능 커밋: `43c1451 feat(game-studio): build visual authoring and playable runtimes` (최신 `front` 재정렬 후 원격 커밋)
- 트러블슈팅: GS-T026~GS-T030

### 원격 PR·파트 이슈 인계 완료 ✅

- 🤖 코드 브랜치 `codex/game-studio-authoring-shell`을 최신 `github/front`(`b92593e`) 위로 재정렬했다. 원격 `front`가 추가한 15개 커밋은 router·Unity Host·기존 shared 영역이었고, 이번 구현은 `festa-frontend/src/game-studio/**` 45개 경로에만 있어 충돌 없이 적용됐다.
- 🤖 코드 커밋 `43c1451`을 원격에 게시하고 `front` 대상 [PR #63](https://github.com/kanghyunsoon/ssafesta/pull/63)을 생성했다. PR 본문은 구현·격리·검증 범위를 기록하고 Workspace 완료 이슈 #49를 병합 시 닫도록 연결했다.
- 🤖 문서 커밋 `bc5d095`를 기존 `codex/game-studio-docs-sync`에 원격 반영해 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)의 spec·JSON Schema·FE 계획·작업일지·트러블슈팅을 최신 구현과 일치시켰다.
- 🤖 [#35](https://github.com/kanghyunsoon/ssafesta/issues/35), [#48](https://github.com/kanghyunsoon/ssafesta/issues/48), [#49](https://github.com/kanghyunsoon/ssafesta/issues/49), [#55](https://github.com/kanghyunsoon/ssafesta/issues/55), [#56](https://github.com/kanghyunsoon/ssafesta/issues/56)에 구현 완료 범위와 남은 Frontend·Backend 연결 경계를 담당자 태그로 갱신했다.
- 🤖 사용자 Asset 운영 경계는 #35의 resolver와 #48의 stable `asset://` allow-list, 공개 Runtime은 #55, Booth 진입은 #56에 이미 포함되어 있어 중복 신규 이슈는 만들지 않았다. PR이 아직 병합되지 않았거나 운영 완료 조건이 남은 이슈는 닫지 않았다.
- 트러블슈팅: GS-T031

## 2026-08-22

### Web Runtime Core front 병합·후속 이슈 상태 정리 ✅

- 🤖 [PR #47](https://github.com/kanghyunsoon/ssafesta/pull/47)이 `@colosair`의 최신 `front` 병합 상태 검증과 승인 후 `front`에 병합됐다. 병합 커밋은 `3b7349f`이며 Vitest **7 files / 29 tests**, build, lint, Booth Studio route 보존, lazy chunk 격리가 재확인됐다.
- 🤖 #35에는 renderer 비의존 기반만 완료됐음을 기록하고 TOP_DOWN renderer, PreviewHost/sandbox, builtin Asset resolver, Preview/Published parity, PLATFORMER adapter가 남아 있어 OPEN을 유지했다.
- 🤖 #49에는 Authoring Workspace가 이제 정식 `front`의 GameProject/Runtime/Event/Dialogue/undo·redo 기반 위에서 진행 가능하다고 공유했다. renderer·Preview·Asset 구현은 #35 경계를 침범하지 않도록 분리했다.
- 🤖 #55에는 play route와 순수 Runtime 기반은 준비됐지만 Published loader·오류 UI·E2E는 #48 Published DTO/오류 코드와 #35 renderer/Asset resolver를 기다린다고 기록했다.
- 🤖 라벨이 없던 Backend #48에 `back`, Frontend #49에 `front` 라벨을 추가했다. #35·#48·#49·#55·#56은 서로 범위가 다르고 완료 조건이 남아 있어 닫거나 합치지 않았다.
- 🤖 최신 `front`와 `back`을 대조한 결과 PR #47 이후 Game Studio 구현 커밋은 없고 Backend #48·Portal #56 구현 PR도 없다. 완료 근거 없는 상태 변경은 하지 않았다.

## 2026-08-21

### 최신 front 재동기화·남은 Runtime 이슈 분리 ✅

- 🤖 `front`에 Booth Studio MVP가 추가되면서 PR #47의 공통 router가 충돌 상태가 된 것을 확인했다. 기존 tip을 `backup/pr47-pre-front-sync-20260821`로 보존하고 Game Studio 4개 커밋을 최신 `front`(`f81aa07`) 위로 다시 배치했다.
- 🤖 `/app/studio/:boothId`와 `/app/games/:gameId/edit|play`를 함께 보존해 충돌을 해소했다. 재검증은 Vitest **7 files / 29 tests**, production build, oxlint 모두 통과했고 PR #47은 다시 `CLEAN/MERGEABLE` 상태다.
- 🤖 구현 PR·커밋·테스트 링크 없이 닫힌 Backend #48을 최신 `back`(`cb84d46`) 소스와 대조했다. Game Studio package, migration, API, 통합 테스트가 없음을 근거로 이슈를 재개하고 Published 응답 DTO·오류 코드 확정을 요청했다.
- 🤖 Unity 없는 Published Web Runtime의 남은 T045·T047~T050을 [#55](https://github.com/kanghyunsoon/ssafesta/issues/55)로 분리해 `@ghkim1632`, `@colosair`에게 배정했다. #48 응답 계약과 #35 renderer/Asset resolver가 확정되기 전에는 필드와 동작을 임의 구현하지 않는다.
- 🤖 #34가 Portal 계약 결정만 닫고 실제 구현을 추적하지 않는 공백을 확인했다. Backend T051·T053·T054와 Frontend T052·T055~T058을 [#56](https://github.com/kanghyunsoon/ssafesta/issues/56)으로 묶어 `@strdeok`, `@ghkim1632`, `@colosair`에게 배정하고 BE whitelist 선배포 순서를 명시했다.
- 🤖 이번 변경은 Frontend 전용 브랜치와 Game Studio 문서 브랜치에서만 수행했다. 공유 `game` 작업트리와 `festa-unity/**`, `backend/**`는 수정하지 않았다.
- 트러블슈팅: GS-T025

### develop 동기화·파트별 SDD 분리·#33/#34 계약 반영 ✅

- 🤖 기존 Foundation tip을 백업한 뒤 새 `codex/game-studio-docs-sync` 브랜치에서 18개 Game Studio 문서 커밋을 최신 `github/develop`(`e515a70`) 위로 재배치했다. 공유 `game` 작업트리와 Unity/Backend 구현은 변경하지 않았다.
- 🤖 develop이 ERD를 `020-erd-schema`로 이동해 019를 Game Studio에 비운 최신 결정을 확인하고, 중간 브랜치의 `020-game-studio`를 최종 `019-game-studio`로 바로잡았다.
- 🤖 PR #44의 다중 파트 SDD 규칙에 따라 루트에는 `spec.md`와 `contracts/`만 두고 실행 산출물 5종을 `FE/` 69개 작업, `BE/` 21개 작업으로 분리했다. Unity는 2D Runtime 소유자가 아니고 AI는 P2 후보라 빈 실행 디렉터리를 만들지 않았다.
- 🤖 #33 합의를 반영해 신규 route/overlay 진입만 REST로 차단하고 loaded 무보상 세션은 완료 허용, 일반 soft/회원 탈퇴 hard delete, Published 이력 유지, Ranking P1 절연을 고정했다.
- 🤖 #34 합의를 반영해 `configId` signed Int32 `1..2147483647`, 0 금지, 내부 BIGINT+공개 INTEGER 분리, `GAME_PORTAL requiresConfig=true`, BE whitelist 선배포를 공통 계약과 API/DB/FE/Unity 문서에 동기화했다.
- 🤖 총 작업 ID는 90개로 유지하고 FE 35/69 완료, BE 0/21 완료로 분리했다. BE #48과 FE #49 담당 구현은 중복 착수하지 않았고 #35 renderer/Preview/Asset 선택만 결정 gate로 남겼다.
- 🤖 원격 `codex/game-studio-docs-sync`를 게시하고 develop 대상 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)을 열었다. 합의가 끝난 #33은 문서 링크와 함께 닫았고 #34도 Backend 확인으로 종료됐다. FE 구현 확인은 #49에서 추적한다.
- 🤖 #34·#35·#48·#49에 최종 spec 번호 019와 `FE/`·`BE/` 실행 문서 경로를 댓글로 공유해 이전 020 경로를 기준으로 구현하는 일을 막았다.
- 트러블슈팅: GS-T023, GS-T024

### Frontend Web Runtime Core·Authoring 기반 구현 ✅

- 🤖 최신 `front`(`da2ea76`)를 기준으로 `codex/game-studio-web-runtime-core` 브랜치를 분리하고 GameProject v1 TypeScript 타입·구조/참조/의미 검증기와 fixture 기반 계약 테스트를 구현했다.
- 🤖 불변 `RuntimeSessionState`, Condition 평가, Action reducer, Event 배열 순서, Action 64회·Scene transition depth 8 예산, 실패 Session 격리, OVERLAY/FULL_SCREEN DIALOGUE 실행기를 구현했다.
- 🤖 `/app/games/:gameId/edit|play` lazy route 경계, 유효 snapshot만 보관하는 undo/redo authoring store, Pickup/Locked Door 편의 필드와 표준 Component/Event 간 왕복 preset recipe를 추가했다.
- 🤖 Unity 없이 `열쇠 → 문 → Overlay → Full-screen Dialogue → 완료` 전체 상태 전이를 통합 검증했다. Vitest **7 files / 29 tests**, TypeScript+Vite production build, oxlint를 모두 통과했고 Edit/Play가 별도 lazy chunk로 출력됨을 확인했다.
- 🤖 구현 변경 23경로는 전부 `festa-frontend/**`이며 `festa-unity/**`와 `backend/**`는 0건이다. 원격 브랜치를 최신 `front` 위로 충돌 없이 재배치하고 `--force-with-lease`로 갱신했다.
- 🤖 Frontend 검토용 [PR #47](https://github.com/kanghyunsoon/ssafesta/pull/47)을 `front` 대상으로 열고, #35에 완료한 비의존 범위와 남은 renderer/Preview/Asset gate를 분리해 공유했다.
- 🤖 #33의 신규 진입 판정 질문에는 전용 게임 소켓 없이 route/overlay 진입 때 REST로 상태를 재검증하고 이미 로드된 무보상 로컬 Session은 완료까지 허용한다고 답변했다. 이후 Backend가 같은 정책과 삭제·이력·Ranking 권장안 전체에 동의했다.
- 🤖 남은 수직 구현을 [#48 Backend Draft/Publish](https://github.com/kanghyunsoon/ssafesta/issues/48)(`@strdeok`)와 [#49 Frontend Workspace](https://github.com/kanghyunsoon/ssafesta/issues/49)(`@busypark`, `@colosair`, `@ghkim1632`)로 분리했다. 결정 gate #33~#35와 구현 추적 #48~#49가 중복되지 않도록 범위를 나눴다.
- 🤖 당시 통합 `tasks.md`의 T010~T013, T017~T024, T026, T077을 완료 처리해 총 90개 중 누적 **35개 완료**로 갱신했다. 현재는 FE/BE tasks로 분리했고 renderer/Preview iframe/Asset resolver #35만 결정 gate로 남았다.
- 트러블슈팅: GS-T020~GS-T022

### 파트 이슈 답변 반영·spec 번호 선제 이동·후속 결정 분리 ✅

- 🤖 결정 반영이 완료된 GitHub #20 Frontend, #21 Backend를 후속 이슈 링크와 함께 닫았다. #22 AI까지 원계약 질의 3건은 모두 종료했으며, 미결정·구현 항목은 #33~#35에서 독립 추적한다.
- 🤖 최신 `origin/develop` 위로 재정렬한 `feature/game-studio-foundation`을 `--force-with-lease`로 원격 동기화했다. 사전 백업 브랜치를 보존했으며 Unity/Game 작업 경로는 포함하지 않았다.
- 🤖 원격 tip과 로컬 HEAD 일치, `origin/develop` 대비 behind 0/ahead 14, 가상 병합 성공, 변경 43경로 중 `festa-unity/**`와 Frontend 구현 경로 0건을 재확인했다. 계약 fixture 7/7과 Runtime trace 6/6도 다시 통과했다.
- 🤖 GitHub #20 Frontend, #21 Backend, #22 AI의 신규 답변을 전체 확인했다. FE는 기존 `festa-frontend` 내부 lazy module과 same-origin Preview, BE는 Draft/Published 2테이블·revision 409·atomic Publish, AI는 P0/P1 비의존과 P2 candidate/patch·spec 007 Job 정책을 확정했다.
- 🤖 당시 Backend 브랜치의 `019-erd-schema`와 번호 충돌을 피하기 위해 Game Studio를 020으로 선제 이동했다. 이후 develop에서 ERD가 `020-erd-schema`로 정리되어 현재 Game Studio 정식 번호는 다시 019다.
- 🤖 완료된 AI 이슈 #22를 닫았다. #20·#21의 남은 항목은 [#33 제품 정책](https://github.com/kanghyunsoon/ssafesta/issues/33), [#34 Portal ID](https://github.com/kanghyunsoon/ssafesta/issues/34), [#35 Studio 내부](https://github.com/kanghyunsoon/ssafesta/issues/35)로 분리했다.
- 🤖 API/DB 계약을 `game_drafts` + `game_published_versions`, `expectedRevision`, 불변 append, nullable 공개본 포인터, Portal `no-store`, builtin Asset MVP로 구체화했다. FE 계획·tasks의 별도 `festa-game-studio/` 가정을 폐기하고 실제 `festa-frontend/src/game-studio/` 경로로 고쳤다.
- 🤖 최신 `origin/develop`(`a8b0398`) 위로 전용 브랜치를 다시 맞췄으며 Game/Unity 작업트리는 변경하지 않았다.
- 🤖 `tasks.md`는 정합성 분석에서 찾은 Guest 권한·자동 보정 금지·lazy chunk 격리·20분 사용성 검증을 보강해 총 90개, 완료 21개로 갱신했다. 계약 fixture 7/7, Runtime trace 6/6, JSON 10/10, task ID 90/90 unique, checklist 16/16을 통과했다.
- 트러블슈팅: GS-T016~GS-T019

### 전체 브랜치 재대조 및 develop 기준선 재정렬 ✅

- 🤖 전체 로컬·원격 브랜치를 갱신해 비교한 결과, 기능 브랜치가 처음 `game` 커밋에서 분기되어 `origin/develop` 기준 변경 목록에 무관한 Unity 경로 **2,057개**가 따라오는 것을 확인했다.
- 🤖 원본 이력을 `backup/game-studio-gamebase-20260821`에 보존한 뒤 Game Studio 9개 커밋만 최신 `origin/develop`(`41b119b`) 위로 재배치했다. 최종 변경은 문서·spec 43개 경로이며 `festa-unity/**` 변경은 **0개**다.
- 🤖 작업트리를 바꾸지 않는 가상 병합으로 `origin/develop` 병합이 충돌 없이 가능한 것을 확인했다. `front/back/ai/game`에 직접 병합하면 각 브랜치의 오래된 공통 문서·삭제 이력 때문에 충돌하므로, 이 브랜치의 병합 대상은 **develop 한 곳**으로 제한한다.
- 🤖 공유 `game` 작업트리와 사용자의 미커밋 Unity Scene은 수정·stage·commit하지 않았다. Game Studio 작업은 별도 worktree에서만 수행했다.
- 🤖 로컬 검증 완료 후 `feature/game-studio-foundation`을 동명의 원격 브랜치에 최초 push하고 upstream 추적을 연결했다.
- 트러블슈팅: GS-T012~GS-T015

### 편집기 시안 반영 및 계약 정합화 ✅

- 🤖 spec 019에 실제 편집기 작업공간(Scene 목록, Object palette, Tile/Object canvas, Properties, Event Editor, Preview/Save/Publish), Asset reference, TOP_DOWN 격자 좌표, preset recipe를 요구사항으로 반영했다.
- 🤖 DIALOGUE를 `OVERLAY`와 `FULL_SCREEN`으로 구분하고 `SHOW_DIALOGUE`, `CLOSE_DIALOGUE`, `GO_TO_SCENE`의 호출·복귀 의미를 고정했다. Web 2D Runtime이 직접 실행하며 Unity는 Portal 진입 트리거만 담당한다는 경계는 유지했다.
- 🤖 JSON Schema의 `nextNodeId` 위치를 Event가 아닌 Dialogue Choice로 바로잡고, asset scheme·Scene 경계·Object 위치 검증과 음수 fixture 2종을 추가했다. Preview와 Published Runtime이 같은 GameProject 해석 결과를 내야 한다는 작업도 명시했다.
- 🤖 상위 서비스·아키텍처·Backend API·DB·Frontend·다음 할 일·팀 결정 문서를 동기화했다. Frontend asset catalog/resolver와 Backend asset metadata·publish validation 책임은 별도 작업으로 나눴다.
- 🤖 갱신된 계약과 미결정 항목을 GitHub [#20 Frontend](https://github.com/kanghyunsoon/ssafesta/issues/20#issuecomment-5364266235), [#21 Backend](https://github.com/kanghyunsoon/ssafesta/issues/21#issuecomment-5364266871), [#22 AI](https://github.com/kanghyunsoon/ssafesta/issues/22#issuecomment-5364267551)에 댓글로 남기고 담당자를 다시 태그했다.
- 🤖 계약 fixture **7/7**, 결정론적 runtime trace **6/6**, 계약 JSON **10/10**을 통과했다. `tasks.md`는 총 80개 중 결정 비의존 작업 **18개 완료**다.
- 🤖 Spec-Kit 비파괴 정합성 분석에서 발견한 branch 표기, Dialogue 종료, Preview/Published 동등성, FE/BE asset 책임 분리 문제를 모두 수정했다.
- 🤖 관련 커밋: `e2b86ad docs(game-studio): align editor and dialogue contracts`, `b4d842e docs(game-studio): sync cross-part architecture`.
- 트러블슈팅: GS-T011

### Game Studio 편집기 시안 구현 가능성 검토 ✅

- 🤖 사용자 제공 시안의 Scene 목록, Object palette, Tile/Object canvas, Properties, Event Editor, Preview/Save/Publish 구성을 spec 019와 대조했다.
- 🤖 시안의 핵심인 `타일 레이어 + Asset 참조 + Object/Component + Trigger/Condition/Action + GameProject JSON + Web Runtime` 흐름은 현재 v1 계약과 일치하며, 첫 MVP 화면 구조로 사용할 수 있음을 확인했다.
- 🤖 실제 구현에서는 브라우저 로컬 `Assets 폴더`를 영구 기준으로 삼지 않고, 기본 asset catalog 또는 서버가 발급한 asset reference를 GameProject가 참조하도록 구분해야 한다. 편집기와 Runtime은 같은 원본 JSON을 소비하되 Runtime은 편집기 상태를 직접 읽지 않는다.
- 🤖 첫 수직 범위는 `TOP_DOWN + DIALOGUE`, 열쇠 획득→문 열기→대화→Scene 이동으로 유지한다. PLATFORMER와 범용 퍼즐 노드 편집기는 MVP 검증 뒤 확장한다.
- 🤖 시안 검토만 수행했으며 spec, schema, 구현 코드는 변경하지 않았다.
- 트러블슈팅: GS-T010

## 2026-08-20

### Game Studio 전용 기록 문서 분리 ✅

- 🤖 사용자 요청에 따라 Game Studio 작업 기록을 일반 Unity/프로젝트 일지에서 분리했다.
- 🤖 기존 `24_작업일지.md`의 Game Studio 3개 작업 섹션을 이 문서로 이동하고, 기존 T-162~T-168은 `28_Game_Studio_트러블슈팅.md`의 `GS-T001~GS-T007`로 재분류했다.
- 🤖 당시 agent 진입 문서, `docs/KHS/README.md`, spec 019의 실행 문서, `docs/22_다음_할일.md`에 전용 기록 경로를 반영했다. 최신 develop 동기화에서는 agent 문서를 수정하지 않고 KHS 문서만 유지했다.
- 🤖 앞으로 Game Studio 작업 완료 시 이 파일만 갱신하고, 일반 `24_작업일지.md`에는 중복 기록하지 않는다.
- 트러블슈팅: GS-T008, GS-T009

### Game Studio 계약 기반·reference Runtime 구현 ✅

- 🤖 파트 답변과 무관한 즉시 작업 9개를 모두 완료했다. `unsupported schema`, `missing start Scene`, `duplicate Object ID`, `invalid DIALOGUE target` 음수 fixture와 기대 error code manifest를 추가했다.
- 🤖 무의존 Node reference validator를 단일 샘플 검사에서 manifest runner로 확장했다. Positive 1건과 Negative 4건이 각각 정확한 code로 실패하는지 확인해 **5/5 통과**, 전체 계약 JSON 문법도 통과했다.
- 🤖 Studio→iframe Preview 계약을 추가했다. exact origin/source 검사, 2 MiB snapshot 상한, requestId lifecycle, Token 금지, 오류 격리와 close cleanup을 정의했다. 실제 origin·배포 단위·CSP 값은 #20 결정을 유지한다.
- 🤖 Event Runtime 의미를 고정했다. Event 배열 순서, Condition AND, Action 순차 적용, terminal Action 마지막 강제, Scene transition 상태 유지, Action 64/transition depth 8 reference budget과 실패 격리를 정의했다.
- 🤖 renderer 없는 reference Runtime과 입력/state trace를 추가했다. `START → ENTER(roomKey) → INTERACT(exitDoor) → CHOOSE(finish)`에서 Scene·Node·변수·inventory·visibility·완료 상태가 **4/4 통과**했다.
- 🤖 지원 major matrix와 Published 원본을 제자리 수정하지 않는 migration policy를 작성했다.
- 🤖 `docs/22_다음_할일.md`에 Game Studio P2의 완료 계약·검증 결과와 #20/#21 이후 실행 순서를 추가했다. `tasks.md`는 71개 중 결정 비의존 작업 **14개 완료**다.
- 🤖 quickstart 예상 결과를 실제 runner 출력과 맞췄다. Unity/Frontend/Backend 구현 파일은 변경하지 않았다.
- 트러블슈팅: GS-T007

### Game Studio 구현 계획·작업분할 + 기능 브랜치 분리 ✅

- 🤖 사용자 요청에 따라 **`feature/game-studio-foundation`** 브랜치를 만들고, 검증된 spec·상위 문서·공통 계약을 커밋했다. 이후 브랜치 오염 점검에서 `game` 기준선 문제를 발견해 develop 위로 재배치했으며 현재 대응 커밋은 `52f6973 docs(game-studio): define web runtime contracts`다.
- 🤖 Spec-Kit plan/tasks 흐름으로 `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `tasks.md`를 작성했다. 파트 답변 없이 가능한 작업과 #20 Frontend·#21 Backend 결정 gate를 파일 경로 단위로 분리했다. #22 AI 답변은 MVP 선행 조건이 아니다.
- 🤖 실제 파트 브랜치를 다시 확인했다. Frontend는 `festa-frontend/`의 React 19.2 + Vite 8.2 + TypeScript 6 + npm, Backend는 `backend/`의 Java 21 + Spring Boot 4.1 + Maven + JPA/Flyway/PostgreSQL/Testcontainers다. 계획은 이 기준을 사용하되 앱 위치·2D renderer와 DB 모델은 담당자 결정을 선점하지 않는다.
- 🤖 구현 순서는 **공통 계약 → 로컬 TOP_DOWN/DIALOGUE 제작·Preview → Draft/Publish → Unity 없는 독립 웹 플레이 → 선택적 Unity Portal → PLATFORMER**로 고정했다.
- 트러블슈팅: GS-T005, GS-T006

### Game Studio P2 스펙·파트 계약·GitHub 이슈 등록 ✅

- 🤖 기존 001~018 기능 spec과 Unity 동결 기준선은 수정하지 않고, 웹 2D UGC 전용 Draft spec을 `019-game-studio`로 신설했다. 첫 범위는 `TOP_DOWN + DIALOGUE`, 후속은 `PLATFORMER`이며 `PUZZLE`은 별도 Runtime이 아니라 Event/Component 조합으로 먼저 검증한다.
- 🤖 실행 경계를 확정 가능한 수준까지 정리했다. **독립 URL은 React 2D Runtime이 바로 실행**하고, 부스 안에서는 `Unity 상호작용 → React Host → Spring Portal Binding → React Runtime` 순서로 진입한다. Unity는 GameProject를 조회·해석·실행하지 않으며 별도 WebGL 게임 Build도 만들지 않는다.
- 🤖 파트 답변이 필요한 계약을 GitHub 이슈로 등록했다 — [#20 Frontend](https://github.com/kanghyunsoon/ssafesta/issues/20)(`@ghkim1632`, `@colosair`), [#21 Backend](https://github.com/kanghyunsoon/ssafesta/issues/21)(`@strdeok`), [#22 AI](https://github.com/kanghyunsoon/ssafesta/issues/22)(담당 계정 미지정).
- 🤖 답변 전 진행 가능한 공통 계약을 작성했다. GameProject JSON Schema, Draft/Publish·Runtime·Portal API 경계, Unity→React Bridge, 파트 책임표와 최소 수직 fixture(`열쇠 → 문 → 대화 → 완료`)를 추가했다.
- 🤖 서비스 기능, 전체 아키텍처, Backend API, DB, Frontend, Unity Client, 팀 결정 필요사항, SDD 분할안, specs 인덱스를 갱신했다. 기존 Booth Studio/Layout/Runtime 계약과 014 Unity 미니게임 책임은 유지했다.
- 트러블슈팅: GS-T001~GS-T004
