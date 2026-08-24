# GameProject v1 계약 Fixture

`minimal-top-down-dialogue.json`은 v1 MVP의 최소 수직 흐름을 고정한다.

```text
TOP_DOWN 방 시작
→ 열쇠 ON_ENTER
→ 아이템 지급 + 열쇠 숨김
→ 문 ON_INTERACT
→ 아이템 조건 확인
→ OVERLAY DIALOGUE 표시
→ 대화 종료 후 같은 방 복귀
→ 열린 문 재상호작용
→ FULL_SCREEN DIALOGUE Scene 이동
→ 게임 완료
```

Frontend Preview/Web Runtime과 Backend Validator는 이 파일을 공통 소비자 계약 테스트에 사용한다.
JSON Schema 통과만으로 Publish할 수 없고, 상위 `README.md`의 의미 검증 규칙도 함께 만족해야 한다.

`validate-fixtures.mjs`는 외부 패키지 없이 참조·중복 ID·Scene 유형·Tile 크기 등 의미 규칙을 검사하는
실행 가능한 예시다. 정식 구현에서는 각 파트의 JSON Schema validator와 이 의미 검사를 함께 사용한다.

## Negative fixtures

`manifest.json`은 positive GameProject와 `invalid/` mutation fixture의 기대 error code를 연결한다.
Negative 파일은 전체 GameProject를 복제하지 않고 `base` fixture에 JSON Pointer mutation을 적용해
오류 하나만 격리한다.

| Fixture | Expected code |
|---|---|
| `invalid/unsupported-schema.json` | `GAME_SCHEMA_UNSUPPORTED` |
| `invalid/missing-start-scene.json` | `START_SCENE_NOT_FOUND` |
| `invalid/duplicate-object-id.json` | `DUPLICATE_OBJECT_ID` |
| `invalid/invalid-dialogue-target.json` | `DIALOGUE_TARGET_INVALID` |
| `invalid/dialogue-next-with-terminal.json` | `DIALOGUE_NEXT_WITH_TERMINAL_ACTION` |
| `invalid/invalid-dialogue-close-context.json` | `DIALOGUE_CLOSE_CONTEXT_INVALID` |

Runner가 다른 code로 실패해도 테스트 실패다. 따라서 consumer마다 첫 오류가 달라지는 계약 drift를 발견한다.

## Runtime trace

`runtime-traces/minimal-top-down-dialogue.trace.json`은 같은 positive project에 다음 입력을 순서대로 적용한다.

```text
START → ENTER(roomKey) → INTERACT(exitDoor) → CHOOSE(continue)
→ INTERACT(exitDoor) → CHOOSE(finish)
```

`reference-runtime.mjs`는 renderer 없는 v1 Event/State 기준 구현이고, `validate-runtime-traces.mjs`가 각 단계의
Scene, active Dialogue Overlay, Dialogue Node, 변수, inventory, visibility, status snapshot을 검사한다. 실제 Frontend Runtime은
구현 언어·라이브러리와 무관하게 같은 trace를 통과해야 한다.
