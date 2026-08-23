# FESTA Game Studio

이 디렉터리는 FESTA Frontend 안에서 lazy load되는 웹 2D Game Studio의 소유 경계다.
Unity는 이 코드를 실행하거나 GameProject를 해석하지 않는다.

## 현재 구현 범위

- GameProject v1 TypeScript 타입, 구조·참조·의미 검증
- 불변 RuntimeSessionState
- Condition/Action reducer와 결정적 Event dispatch
- Action 64회, Scene transition depth 8의 실행 예산
- OVERLAY/FULL_SCREEN DIALOGUE 실행
- GameProject undo/redo authoring store
- Scene/Object/Tile/Properties/Event/Dialogue/Data authoring workspace
- Pickup/Locked Door와 액션 오브젝트의 Component/Event recipe
- TOP_DOWN/PLATFORMER reference renderer와 중력·점프·체력·투사체·Spawner
- OVERLAY/FULL_SCREEN 대화 배경·인물·표정·선택지 연출
- 6종 시작 템플릿과 한국어 guide/tutorial
- 첫 방문 `열쇠 → 문 → 대화` 6단계 실습과 대상 자동 찾기
- Object Layer 검색, 편집 잠금/숨김, z-index 정렬과 브라우저별 편집 상태 보존
- 타일 드래그의 animation-frame 단위 일괄 commit과 500 Object 편집 100ms 예산 테스트
- versioned builtin Asset catalog와 IndexedDB local replacement resolver
- JSON 2MB, Scene 50, Object 500/Scene, Event 300/Scene, Asset 300 상한
- `/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route 경계
- revision-aware Draft/Publish adapter와 충돌 시 로컬 JSON 백업 복구 UX
- Published loader, 손상/미지원 schema 오류 격리, Booth Portal GAME overlay
- 내 이미지 게시 blocker의 Scene/Object/Item별 사용 위치 안내

## 아직 연결하지 않은 외부 경계

- 서버 소유 Asset upload/reference resolver
- Booth Portal API 구현과 Draft/Publish/Published 서버 endpoint
- Coin/Reward나 서버 권위 점수

외부 경계는 `studio/ports`, `runtime/ports` adapter로 교체한다. GameProject를 Unity WebGL에 넘기거나
Unity에서 2D 게임을 실행하지 않는다. Local Preview는 Backend와 Unity 없이 같은 validator/runtime으로 검증한다.

## 연결 스위치와 QA

- `VITE_GAME_STUDIO_API_ENABLED=true`: 편집기의 Draft/Publish를 서버 adapter로 전환한다.
- `/app/games/:gameId/play`: Published 조회만 사용한다.
- `/app/games/:gameId/play?source=local`: 현재 편집 snapshot 플레이 테스트다.
- `&perf=1`: 로컬 플레이 테스트에 렌더 FPS 측정값을 표시한다. 백그라운드 탭은 브라우저가 FPS를 제한하므로 활성 탭에서 측정한다.
- 좁은 브라우저에서는 PC 편집기의 1040px 작업 폭을 유지해 패널이 서로 겹치지 않으며 가로 탐색으로 접근한다.

## 검증

```bash
npm test
npm run build
npm run lint
```
