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

현재 Authoring Quality 브랜치 기준은 test 49 files/269 tests, build, lint 통과다. 빌드 결과에서 Edit/Play가 일반 FESTA entry와
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
6. Scene 전체 복제 뒤 Object/Event/Dialogue ID가 겹치지 않는지 확인하고 ↑/↓로 제작 순서만 바뀌며 시작 Scene은 유지되는지 확인한다.
7. Object를 Ctrl+C로 복사하고 다른 World Scene에서 Ctrl+V로 붙여넣어 상대 배치와 Object Trigger Event가 함께 보존되는지 확인한다.
8. 타일맵에서 `B` 브러시, `R` 사각형, `F` 연결 영역 채우기, `I` 스포이드를 전환한다. 오브젝트 위에서도 타일 gesture가 끊기지 않고 사각형 preview와 선택 타일 복귀가 보여야 한다.
9. `충돌 영역`을 켜 Collider Component가 있는 오브젝트만 강조되고 Project JSON이 바뀌지 않는지 확인한다.
10. `Q` 선택, `W` 화면 이동, `G` 격자를 전환하고 빈 영역 drag 선택, 선택 수 안내, locked Object 제외를 확인한다.
11. Scene 속성에서 맵 크기를 늘려 기존 Tile/Object가 유지되는지 확인한다. 축소는 별도 테스트 프로젝트에서만 수행한다.
12. 1024px에서 Canvas 도구가 모두 보이고, 800px에서 문서 전체 가로 스크롤 없이 도구줄만 내부 이동하며 `화면 넓게` 또는 Shift+F로 양쪽 panel을 복구할 수 있는지 확인한다.
13. 레이어 패널에서 ID 검색, 편집 숨김, 잠금, 표시 순서 변경을 확인한다. 잠금은 새로고침 뒤에도 유지돼야 한다.
14. 플레이 테스트를 눌러 `/app/games/123/play?source=local`에서 열쇠 획득·대화·문 이동·완료를 실행한다.
15. 슈팅 템플릿의 데이터 탭에서 `적 3명 처치`, 생존 템플릿에서 `30초 생존/체력 0 종료`가 보이는지 확인한다. 목표값, ALL/ANY, 재시작/도전 실패를 바꿔 저장할 수 있어야 한다.
16. Mock 모드에서 `게시하기`를 누르고 `게시본 확인 v1`으로 `/app/games/123/play`에 진입한다. 제목·Scene·목표 HUD가 방금 게시한 snapshot과 같아야 하며 다시 게시하면 v2가 된다.
17. 저장하지 않은 제목 또는 배치를 만든 뒤 1초 안에 `임시 복구 HH:mm`이 보이는지 확인한다. 다른 FESTA 화면으로 나갔다 편집기를 다시 열면 저장본은 그대로 유지되고 `로컬 안전 복구`에서 복구·폐기·JSON 보관을 선택할 수 있어야 한다. 복구 뒤 명시 저장하면 안내가 재발하지 않아야 한다.
18. 편집기의 `성능 점검`은 `/play?source=local&perf=1`로 진입해 활성 탭 FPS, p95 frame time, 느린 frame 비율을 표시한다. 목표는 55fps 이상, p95 18.2ms 이하, 느린 frame 5% 이하이며 비활성 탭에서는 측정 일시정지가 보여야 한다.
19. 100×50 맵을 적용하면 Canvas가 3200×1600 작업 공간으로 펼쳐지고 중앙 영역만 scroll되는지 확인한다. 5,000 Tile 전체 채우기 뒤 화면 badge와 실제 `.gss-tile-layer > span` 수가 전체 5,000개가 아니라 viewport 주변 수백 개로 유지되어야 한다.
20. 큰 맵의 먼 위치로 이동한 뒤 레이어 검색에서 화면 밖 Object를 선택하면 Canvas가 해당 위치로 자동 이동해야 한다. 레이어가 80개를 넘으면 `더 보기`로 점진 표시되며 검색은 아직 표시하지 않은 Object까지 포함해야 한다.
21. PLATFORMER 크기에 200×100을 입력하면 `현재 20,000칸`과 10,000칸 저장 한도가 표시되고 적용 버튼이 비활성화되어야 한다. 200×50은 적용 가능해야 한다.
22. `VITE_USE_MOCK=false`, `VITE_GAME_STUDIO_API_ENABLED=true`에서는 `/play`가 실제 Published API 오류를 한국어로 격리하고 렌더 반복 오류가 없어야 한다.
23. 개발 모드에서 `/app/games/9302/edit?fixture=max`를 열면 100×100, Object 500개, Tile 10,000칸 fixture가 계약 검증을 통과해 열려야 한다. 이 fixture는 운영 저장용이 아니므로 저장·게시 버튼으로 운영 데이터를 만들지 않는다.
24. `전체`를 누르면 약 15% 배율에서 전체 맵이 보이고 badge가 `타일 10000칸 합성`, `data-rendered-tile-count=0`을 표시해야 한다. `1:1`에서는 viewport 주변 수백 Tile만 DOM으로 돌아와야 한다. 미니맵 중앙을 누르면 화면 Object ID 구성이 중간 좌표대로 바뀌어야 한다.
25. Dialogue Scene의 `대화 흐름`에서 시작 Node 도달 수와 선택 결과가 보이고, Node card를 누르면 같은 Node 편집 폼으로 이동해야 한다. 테스트 fixture의 도달 불가·선택지 없음은 warning으로 집계하되 JSON에 분석 결과를 저장하지 않는다.
26. 데이터 탭의 `게임 완성도 점검`은 시작 위치·완료 경로·상호작용·대화·Scene·Asset 6가지를 표시한다. Player Sprite 속성에서는 4방향 clip을 바꾸고 재생/일시정지할 수 있으며 Runtime 자동 방향 선택 안내가 보여야 한다.

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

- Frontend 기준 구현은 GitLab `feature/game-studio-web-runtime`, 후속 편집 품질 작업은 `feat/S15P21A604-156-game-studio-authoring-quality`에서 실행하고 완료 뒤 `front` 대상 MR로 Squash Merge한다.
- 문서 변경은 `docs/S15P21A604-156-game-studio-authoring-quality`에서 실행하고 `develop` 대상 MR로 Squash Merge한다.
- 로컬 원격 별칭은 `gitlab`을 정본으로 사용한다. 이 worktree의 `origin`은 바탕화면 로컬 저장소이므로 push 대상으로 사용하지 않는다.
- `npm test`, `npm run build`, `npm run lint`와 Mock 게시 browser smoke를 동일 기준으로 유지한다.
