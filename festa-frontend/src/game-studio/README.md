# FESTA Game Studio

이 디렉터리는 FESTA Frontend 안에서 lazy load되는 웹 2D Game Studio의 소유 경계다.
Unity는 이 코드를 실행하거나 GameProject를 해석하지 않는다.

## 현재 구현 범위

- GameProject v1.0/v1.1 TypeScript 타입, 구조·참조·의미 검증과 v1.0 편집본 자동 업그레이드
- 불변 RuntimeSessionState
- Condition/Action reducer와 결정적 Event dispatch
- Action 64회, Scene transition depth 8의 실행 예산
- OVERLAY/FULL_SCREEN DIALOGUE 실행
- GameProject undo/redo authoring store
- Scene/Object/Tile/Properties/Event/Dialogue/Data authoring workspace
- Pickup/Locked Door와 액션 오브젝트의 Component/Event recipe
- TOP_DOWN/PLATFORMER reference renderer와 중력·점프·체력·투사체·Spawner
- 점수·적 처치·생존 시간 목표, ALL/ANY 조합, 재시작/도전 실패 규칙과 Runtime HUD
- OVERLAY/FULL_SCREEN 대화 배경·인물·표정·선택지 연출
- 6종 시작 템플릿과 한국어 guide/tutorial
- 첫 방문 `열쇠 → 문 → 대화` 6단계 실습과 대상 자동 찾기
- Object Layer 검색, 편집 잠금/숨김, z-index 정렬과 브라우저별 편집 상태 보존
- Scene 전체 복제·순서 변경과 Scene 사이 Object/Event 복사·붙여넣기
- Tile 브러시·사각형·연결 영역 채우기·스포이드와 Collider 편집 가이드
- 타일 드래그의 animation-frame 단위 일괄 commit과 500 Object 편집 100ms 예산 테스트
- 32px cell 기반 대형 맵 작업 공간, viewport Object/Tile overscan rendering, 레이어 원거리 Object 자동 탐색
- versioned builtin Asset catalog와 IndexedDB local replacement resolver
- JSON 2MB, Scene 50, Object 500/Scene, Event 300/Scene, Asset 300, TileLayer 10,000칸 상한
- `/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route 경계
- Mock 모드의 `초안 저장 → 버전 게시 → 일반 /play 주소` 전체 사용자 여정
- revision-aware Draft/Publish adapter와 충돌 시 로컬 JSON 백업 복구 UX
- 저장 전 변경을 800ms 지연으로 이 기기에 보관하는 복구 저널, 이탈 경고, 복구/폐기/JSON 보관 UX
- Published loader, 손상/미지원 schema 오류 격리, Booth Portal GAME overlay
- 내 이미지 게시 blocker의 Scene/Object/Item별 사용 위치 안내
- 활성 탭 FPS·p95 frame time·느린 frame 비율을 함께 표시하는 플레이 성능 점검

## 아직 연결하지 않은 외부 경계

- 서버 소유 Asset upload/reference resolver
- Booth Portal API 구현과 Draft/Publish/Published 서버 endpoint
- Coin/Reward나 서버 권위 점수

외부 경계는 `studio/ports`, `runtime/ports` adapter로 교체한다. GameProject를 Unity WebGL에 넘기거나
Unity에서 2D 게임을 실행하지 않는다. Local Preview는 Backend와 Unity 없이 같은 validator/runtime으로 검증한다.

## 연결 스위치와 QA

- `VITE_GAME_STUDIO_API_ENABLED=true`: 편집기의 Draft/Publish를 서버 adapter로 전환한다.
- `VITE_USE_MOCK=true`: 브라우저 게시본 저장소를 사용해 서버 없이 게시·공개 플레이 E2E를 검증한다.
- `/app/games/:gameId/play`: Published 조회만 사용한다.
- `/app/games/:gameId/play?source=local`: 현재 편집 snapshot 플레이 테스트다.
- 편집기의 `성능 점검`: 로컬 플레이에 FPS·p95 frame time·느린 frame 비율을 표시한다. 비활성 탭에서는 측정을 멈추며 목표는 55fps 이상, p95 18.2ms 이하, 느린 frame 5% 이하다.
- 760~1039px compact 창은 문서 전체 가로 이동 없이 중앙 Canvas만 scroll하며, Shift+F 집중 모드로 양쪽 panel을 접는다. 권장 작업 폭은 1280px 이상이다.

## 검증

```bash
npm test
npm run build
npm run lint
```
