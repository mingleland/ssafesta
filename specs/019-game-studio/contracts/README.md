# Game Studio 계약 인덱스

> 상태: Draft — GitHub Issues #20, #21, #22 검토 대기

| 계약 | 파일 | Producer | Consumer |
|---|---|---|---|
| GameProject v1 | `game-project-v1.schema.json` | Studio / Spring Published API | Preview / Web Runtime / Spring Validator |
| Game API 경계 | `game-api.md` | Spring | FESTA Web / Game Studio / Web Runtime |
| Booth Game Portal | `game-portal-bridge.md` | Unity WebGL | FESTA React Host |
| Preview Protocol | `game-preview-protocol.md` | Studio | Preview Web Runtime |
| Event Runtime | `event-runtime-semantics.md` | Contract owner | Studio / Runtime / Spring Validator |
| 파트 책임 | `part-boundaries.md` | FE·BE·AI·Unity 합의 | 전 파트 |
| 최소 수직 Fixture | `fixtures/minimal-top-down-dialogue.json` | 계약 담당 | Studio / Runtime / Spring Validator |

## 계약 원칙

1. Booth Layout JSON과 GameProject JSON을 합치지 않는다.
2. Unity는 GameProject를 조회·파싱·실행하지 않는다.
3. Studio와 Runtime은 같은 GameProject major version을 사용한다.
4. JSON Schema 검증 뒤에도 ID 참조, 시작 Scene, Event 순환 등 의미 검증을 수행한다.
5. Runtime은 AI 서버를 호출하지 않는다.
6. Published Version은 불변이다.
7. 계약 변경은 영향 파트 합의와 fixture 기반 소비자 검증을 거친다.

## v1 의미 검증 규칙

JSON Schema만으로 표현하기 어려워 Producer와 서버가 별도로 검사한다.

- `startSceneId`는 `scenes[].id` 중 정확히 하나를 가리킨다.
- 모든 Scene·Object·Variable·Item·Event ID는 각 namespace에서 중복되지 않는다.
- `targetId`, `sceneId`, `variableId`, `itemId`는 존재하는 대상을 가리킨다.
- `TOP_DOWN` Scene은 Player Spawn을 정확히 하나 가진다.
- `TOP_DOWN.tileLayers[].data` 길이는 Scene의 `width × height`와 같다.
- Object Component가 참조하는 Asset·Item은 존재하며, 같은 Object에 동일 Component type을 중복하지 않는다.
- `SHOW_DIALOGUE.sceneId`는 `DIALOGUE` Scene을 가리키고, `nextNodeId`는 같은 Scene의 Node를 가리킨다.
- Variable의 `type`과 `initialValue` 실제 타입은 일치한다.
- Event 순서, terminal Action, Action/transition budget은 `event-runtime-semantics.md`를 따른다.

## Version 정책

- `schemaVersion`은 `MAJOR.MINOR.PATCH` 문자열이다.
- Runtime은 지원하지 않는 MAJOR를 거부한다.
- 호환 가능한 선택 필드 추가는 MINOR, 기존 의미 변경은 MAJOR를 올린다.
- `revision`은 Draft 편집 충돌 탐지용이며 Published Version 번호와 다르다.

## Scene/Component 적용 판단

- `TOP_DOWN`과 후속 `PLATFORMER`만 이동·물리 Runtime 유형으로 본다.
- `DIALOGUE`는 이동 물리가 없는 Scene 유형이며 Node/Choice 그래프로 실행한다.
- `PUZZLE`은 v1의 별도 Scene 유형으로 만들지 않는다. 변수·아이템·Event·Object Component 조합으로 먼저 검증하고, 조합만으로 표현할 수 없는 퍼즐이 반복해서 확인될 때 확장한다.
- `preset`은 제작 UI의 빠른 시작값이고, 실행 능력은 허용 목록의 typed `components`와 `events`가 결정한다.
