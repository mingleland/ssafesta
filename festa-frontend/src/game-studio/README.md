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
- versioned builtin Asset catalog와 IndexedDB local replacement resolver
- JSON 2MB, Scene 50, Object 500/Scene, Event 300/Scene, Asset 300 상한
- `/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route 경계

## 아직 연결하지 않은 외부 경계

- Spring revision-aware Draft/Publish와 Published loader
- 서버 소유 Asset upload/reference resolver
- Booth Portal resolver와 Unity→React 실행 trigger
- Coin/Reward나 서버 권위 점수

외부 경계는 `studio/ports`, `runtime/ports` adapter로 교체한다. GameProject를 Unity WebGL에 넘기거나
Unity에서 2D 게임을 실행하지 않는다. Local Preview는 Backend와 Unity 없이 같은 validator/runtime으로 검증한다.

## 검증

```bash
npm test
npm run build
npm run lint
```
