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

현재 Local Publish Loop 브랜치 기준은 test 38 files/204 tests, build, lint 통과다. 빌드 결과에서 Edit/Play가 일반 FESTA entry와
분리된 lazy chunk인지 함께 확인한다.

## 3. Backend 없이 편집·플레이 확인

```powershell
Set-Location festa-frontend
$env:VITE_USE_MOCK='true'
$env:VITE_GAME_STUDIO_API_ENABLED='false'
npm run dev -- --host 127.0.0.1 --port 5174
```

1. `/login`에서 로컬 mock 회원으로 진입한다.
2. `/app/games/123/edit` 첫 방문 guide에서 단계별 튜토리얼을 시작한다.
3. 탐색·플랫폼 템플릿 각각에서 시작 맵 → 오브젝트 → 모습 → 동작 → 저장·플레이 순서로 “화면에서 해당 요소 찾기”와 다음 단계가 동작하는지 확인한다.
4. 시작 템플릿에서 6개 16:9 미리보기·난이도·예상 시간이 보이고, Object 속성의 재료함에서 캐릭터/사물/내 이미지 분류와 한국어 검색이 동작하는지 확인한다.
5. Canvas에서 Object 두 개를 Shift 선택하고 drag/방향키로 상대 간격을 유지해 이동한다. Ctrl+D 뒤 Object와 해당 Trigger Event가 함께 새 ID로 복제되는지 확인한다.
6. `Q` 선택, `W` 화면 이동, `G` 격자를 전환하고 빈 영역 drag 선택, 선택 수 안내, locked Object 제외를 확인한다.
7. Scene 속성에서 맵 크기를 늘려 기존 Tile/Object가 유지되는지 확인한다. 축소는 별도 테스트 프로젝트에서만 수행한다.
8. 1024px에서 Canvas 도구가 모두 보이고, 800px에서 문서 전체 가로 스크롤 없이 도구줄만 내부 이동하며 `화면 넓게` 또는 Shift+F로 양쪽 panel을 복구할 수 있는지 확인한다.
9. 레이어 패널에서 ID 검색, 편집 숨김, 잠금, 표시 순서 변경을 확인한다. 잠금은 새로고침 뒤에도 유지돼야 한다.
10. 플레이 테스트를 눌러 `/app/games/123/play?source=local`에서 열쇠 획득·대화·문 이동·완료를 실행한다.
11. 슈팅 템플릿의 데이터 탭에서 `적 3명 처치`, 생존 템플릿에서 `30초 생존/체력 0 종료`가 보이는지 확인한다. 목표값, ALL/ANY, 재시작/도전 실패를 바꿔 저장할 수 있어야 한다.
12. Mock 모드에서 `게시하기`를 누르고 `게시본 확인 v1`으로 `/app/games/123/play`에 진입한다. 제목·Scene·목표 HUD가 방금 게시한 snapshot과 같아야 하며 다시 게시하면 v2가 된다.
13. `?source=local&perf=1`은 활성 PC 탭에서만 FPS 진단에 사용한다. 자동화 백그라운드 탭의 1fps throttling은 제품 성능으로 기록하지 않는다.
14. `VITE_USE_MOCK=false`, `VITE_GAME_STUDIO_API_ENABLED=true`에서는 `/play`가 실제 Published API 오류를 한국어로 격리하고 렌더 반복 오류가 없어야 한다.

사람 대상 20분 검증 기록지는 [usability-test.md](usability-test.md)를 사용한다.

## 4. Manual vertical scenario

`contracts/fixtures/minimal-top-down-dialogue.json`으로 다음 흐름을 실행한다.

1. `room` TOP_DOWN Scene에서 시작한다.
2. `roomKey` 진입으로 key를 받고 Object를 숨긴다.
3. `exitDoor` 상호작용에서 `HAS_ITEM(key)`를 통과한다.
4. OVERLAY dialogue 중 `currentSceneId=room`을 유지하고 world input을 막는다.
5. CLOSE_DIALOGUE 뒤 같은 방으로 복귀한다.
6. FULL_SCREEN ending으로 이동해 COMPLETE_GAME을 실행한다.

## 5. Integration acceptance

- Guest는 edit/save/publish를 할 수 없고 Published play는 허용된다.
- route/overlay 진입 때 REST 조회로 공개·임대·Binding 상태를 확인한다.
- 비공개 전환 뒤 새 진입은 안내 화면으로 막고 이미 로드된 무보상 세션은 중단시키지 않는다.
- Preview와 Published Runtime의 최종 RuntimeSessionState가 같다.
- Game Runtime 실패가 Unity 월드와 다른 FESTA route로 전파되지 않는다.

Backend 검증 절차는 [BE quickstart](../BE/quickstart.md)를 따른다.

## 6. GitLab 이관 후 실행 기준

이관·브랜치 정리 결과는 [Game Studio GitLab 이관 기록](../../../docs/KHS/29_Game_Studio_GitLab_이관_준비.md)의
ref·Issue 대응표와 검증 체크리스트를 사용한다.

- Frontend 검증은 GitLab `feature/game-studio-web-runtime`에서 실행하고 완료 뒤 `front` 대상 MR로 Squash Merge한다.
- 문서 변경은 `docs/game-studio-contract-status`에서 실행하고 `develop` 대상 MR로 Squash Merge한다.
- 로컬 원격 별칭은 `gitlab`을 정본으로 사용한다. 이 worktree의 `origin`은 바탕화면 로컬 저장소이므로 push 대상으로 사용하지 않는다.
- `npm test`, `npm run build`, `npm run lint`와 Mock 게시 browser smoke를 동일 기준으로 유지한다.
