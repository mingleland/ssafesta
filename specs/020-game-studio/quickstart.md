# Quickstart: Game Studio 계약 검증

## 현재 실행 가능한 범위

이 가이드는 Frontend/Backend 이슈 답변 없이도 실행 가능한 공통 계약 검증만 다룬다.
Unity Editor, WebGL Build, Spring, AI 서버는 필요 없다.

## 1. JSON 문법 검증

PowerShell에서 저장소 루트를 기준으로 실행한다.

```powershell
Get-Content -Raw -Encoding UTF8 specs/020-game-studio/contracts/game-project-v1.schema.json | ConvertFrom-Json | Out-Null
Get-Content -Raw -Encoding UTF8 specs/020-game-studio/contracts/fixtures/minimal-top-down-dialogue.json | ConvertFrom-Json | Out-Null
```

예상 결과: 출력과 오류가 없다.

## 2. 의미 계약 검증

Node.js 18 이상에서 실행한다.

```powershell
node specs/020-game-studio/contracts/fixtures/validate-fixtures.mjs
```

예상 결과:

```text
PASS positive minimal-top-down-dialogue
PASS negative unsupported-schema -> GAME_SCHEMA_UNSUPPORTED
PASS negative missing-start-scene -> START_SCENE_NOT_FOUND
PASS negative duplicate-object-id -> DUPLICATE_OBJECT_ID
PASS negative invalid-dialogue-target -> DIALOGUE_TARGET_INVALID
PASS negative dialogue-next-with-terminal -> DIALOGUE_NEXT_WITH_TERMINAL_ACTION
PASS negative invalid-dialogue-close-context -> DIALOGUE_CLOSE_CONTEXT_INVALID
GameProject contract fixtures: 7/7 passed
```

검증 대상은 시작 Scene, 중복 ID, Asset/Item/Variable/Object 참조, Player Spawn 수, Tile 크기,
Component 중복, DIALOGUE 대상, Node 이동과 terminal Action 순서다. Negative fixture는 다른 오류가
발생해도 실패하므로 Frontend/Backend의 validation 순서 차이도 드러낸다.

Fixture 목록과 기대 code는 `contracts/fixtures/manifest.json`이 소유한다.

## 3. Runtime 상태 전이 검증

```powershell
node specs/020-game-studio/contracts/fixtures/validate-runtime-traces.mjs
```

예상 결과:

```text
PASS runtime step 1 START
PASS runtime step 2 ENTER
PASS runtime step 3 INTERACT
PASS runtime step 4 CHOOSE
PASS runtime step 5 INTERACT
PASS runtime step 6 CHOOSE
GameProject runtime trace: 6/6 passed
```

## 4. 수동 수직 시나리오

`contracts/fixtures/minimal-top-down-dialogue.json`을 읽어 다음 순서가 데이터만으로 표현되는지 확인한다.

1. `room` TOP_DOWN Scene에서 Player Spawn.
2. `roomKey` 진입으로 `key` 지급 및 오브젝트 숨김.
3. `exitDoor` 상호작용에서 `HAS_ITEM(key)` 통과.
4. `doorHint` OVERLAY DIALOGUE 표시. 이때 `currentSceneId=room`과 기존 상태를 유지한다.
5. `CLOSE_DIALOGUE`로 같은 방에 복귀한다.
6. 열린 문을 다시 상호작용해 `ending` FULL_SCREEN DIALOGUE로 이동한다.
7. 선택지에서 `COMPLETE_GAME`을 실행한다.

## 5. 파트별 구현 후 추가할 검증

- FE 구현: Studio→same-origin Preview snapshot, `/app/games/:gameId/play`, Overlay 종료/복귀 E2E
- BE 구현: revision conflict, invalid Publish 거부, Published 불변, atomic pointer update integration test
- #34 이후: Portal resolution과 `GAME_PORTAL`/signed Int32 `configId` integration test
- 선택적 Unity 연동 이후: 부스 진입·종료 후 월드 연결과 위치 유지
- #22 확정 필수 검증: AI 서버 중단 상태에서 create/save/publish/play 성공

## 완료 판정

- positive fixture가 모든 consumer에서 통과한다.
- negative fixture마다 기대한 구조/의미 오류 코드가 나온다.
- Unity 없이 Published 게임을 끝까지 플레이할 수 있다.
- 한 게임의 load/runtime 실패가 FESTA Host와 다른 게임에 전파되지 않는다.
