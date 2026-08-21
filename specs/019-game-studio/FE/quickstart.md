# FE Quickstart: Contract and Web Runtime

## 1. Shared contract verification

저장소 루트에서 실행한다. Unity, Spring, AI 서버는 필요 없다.

```powershell
Get-Content -Raw -Encoding UTF8 specs/019-game-studio/contracts/game-project-v1.schema.json | ConvertFrom-Json | Out-Null
node specs/019-game-studio/contracts/fixtures/validate-fixtures.mjs
node specs/019-game-studio/contracts/fixtures/validate-runtime-traces.mjs
```

예상 결과는 fixture 7/7, Runtime trace 6/6 통과다.

## 2. Frontend core verification

PR #47 또는 해당 코드가 반영된 브랜치에서 실행한다.

```powershell
Set-Location festa-frontend
npm test
npm run build
npm run lint
```

현재 기준은 test 7 files/29 tests, build, lint 통과다. 빌드 결과에서 Edit/Play가 일반 FESTA entry와
분리된 lazy chunk인지 함께 확인한다.

## 3. Manual vertical scenario

`contracts/fixtures/minimal-top-down-dialogue.json`으로 다음 흐름을 실행한다.

1. `room` TOP_DOWN Scene에서 시작한다.
2. `roomKey` 진입으로 key를 받고 Object를 숨긴다.
3. `exitDoor` 상호작용에서 `HAS_ITEM(key)`를 통과한다.
4. OVERLAY dialogue 중 `currentSceneId=room`을 유지하고 world input을 막는다.
5. CLOSE_DIALOGUE 뒤 같은 방으로 복귀한다.
6. FULL_SCREEN ending으로 이동해 COMPLETE_GAME을 실행한다.

## 4. Integration acceptance

- Guest는 edit/save/publish를 할 수 없고 Published play는 허용된다.
- route/overlay 진입 때 REST 조회로 공개·임대·Binding 상태를 확인한다.
- 비공개 전환 뒤 새 진입은 안내 화면으로 막고 이미 로드된 무보상 세션은 중단시키지 않는다.
- Preview와 Published Runtime의 최종 RuntimeSessionState가 같다.
- Game Runtime 실패가 Unity 월드와 다른 FESTA route로 전파되지 않는다.

Backend 검증 절차는 [BE quickstart](../BE/quickstart.md)를 따른다.
