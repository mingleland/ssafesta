# FESTA Game Studio Web Core

이 디렉터리는 FESTA Frontend 안에서 lazy load되는 웹 2D Game Studio의 소유 경계다.
Unity는 이 코드를 실행하거나 GameProject를 해석하지 않는다.

## 현재 구현 범위

- GameProject v1 TypeScript 타입, 구조·참조·의미 검증
- 불변 RuntimeSessionState
- Condition/Action reducer와 결정적 Event dispatch
- Action 64회, Scene transition depth 8의 실행 예산
- OVERLAY/FULL_SCREEN DIALOGUE 실행
- GameProject undo/redo authoring store
- Pickup/Locked Door 편의 preset과 표준 Component/Event 간 왕복 변환
- `/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route 경계

## 의도적으로 제외한 범위

- TOP_DOWN 및 PLATFORMER renderer/physics adapter
- iframe Preview host와 sandbox 정책
- Asset resolver
- Draft/Publish API 및 Unity Portal 연결

위 항목은 각각의 계약 결정과 파트 구현 브랜치에서 결합한다. Web Core는 renderer, Backend,
Unity 없이 `npm test`로 검증할 수 있다.

## 검증

```bash
npm test
npm run build
npm run lint
```
