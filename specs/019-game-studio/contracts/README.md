# Game Studio 계약 인덱스

> 상태: Draft v0.5 — #20~#22·#33~#35·#48·#58 반영, v1.1 FE candidate 구현 / 운영 연결은 #48·#55·#56·#69·#78·#81

| 계약 | 파일 | Producer | Consumer |
|---|---|---|---|
| GameProject v1 | `game-project-v1.schema.json` | Studio / Spring Published API | Preview / Web Runtime / Spring Validator |
| Game API 경계 | `game-api.md` | Spring | FESTA Web / Game Studio / Web Runtime |
| Booth Game Portal | `game-portal-bridge.md` | Unity WebGL | FESTA React Host |
| Preview Protocol | `game-preview-protocol.md` | Studio | Preview Web Runtime |
| Event Runtime | `event-runtime-semantics.md` | Contract owner | Studio / Runtime / Spring Validator |
| Studio/Asset 모델 | `studio-authoring-model.md` | Studio / Asset catalog | Preview / Runtime / Spring Validator |
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
8. GameProject에는 Asset binary나 완성 화면 캡처를 넣지 않고 안정적인 Asset reference만 둔다.
9. Draft와 Published는 별도 저장 모델이며 Publish는 불변 version append와 pointer 갱신을 단일 트랜잭션으로 수행한다.
10. Preview는 FESTA Web same-origin에서 실행하고 Published Runtime과 같은 validator/state/event core를 사용한다.
11. 신규 실행은 route/overlay REST 조회에서 판정하고 이미 로드된 무보상 로컬 세션은 완료까지 허용한다.
12. Portal `configId`는 signed Int32 양수이며 DB 내부 BIGINT PK와 별도 INTEGER 공개 ID로 관리한다.
13. 일반 삭제는 soft, 회원 탈퇴는 관련 데이터를 hard delete하고 Published 이력은 Game 존속 중 유지한다.
14. Draft 저장과 Publish는 같은 v1 상한·내부 참조·안정 Asset source 규칙을 적용하며 서버가 자동 보정하지 않는다.

## v1 의미 검증 규칙

JSON Schema만으로 표현하기 어려워 Producer와 서버가 별도로 검사한다.

- `startSceneId`는 `scenes[].id` 중 정확히 하나를 가리킨다.
- 모든 Scene·Object·Variable·Item·Event ID는 각 namespace에서 중복되지 않는다.
- `targetId`, `sceneId`, `variableId`, `itemId`는 존재하는 대상을 가리킨다.
- `TOP_DOWN`/`PLATFORMER` Scene은 Player Spawn을 정확히 하나 가진다.
- World Scene의 `tileLayers[].data` 길이는 Scene의 `width × height`와 같다.
- Object Component가 참조하는 Asset·Item은 존재하며, 같은 Object에 동일 Component type을 중복하지 않는다.
- `SHOW_DIALOGUE.sceneId`는 `OVERLAY` DIALOGUE를 가리키며, Overlay는 시작 Scene이나 `GO_TO_SCENE` 대상이 될 수 없다.
- `CLOSE_DIALOGUE`는 `OVERLAY` DIALOGUE Choice에서만 허용하고, `nextNodeId`는 같은 Scene의 Node를 가리킨다.
- World Object 위치는 Scene 범위 안의 0-based 정수 셀 좌표다.
- 저장되는 Asset source는 `builtin://` 또는 서버가 발급한 stable `asset://` reference다. `asset://local`, binary/base64, `data:`, `blob:`, `file:`과 임시 서명 URL은 Draft/Publish에서 거부한다.
- Draft와 Publish 모두 JSON 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300 상한을 적용한다.
- Variable의 `type`과 `initialValue` 실제 타입은 일치한다.
- Event 순서, terminal Action, Action/transition budget은 `event-runtime-semantics.md`를 따른다.

## Version 정책

- `schemaVersion`은 `MAJOR.MINOR.PATCH` 문자열이다.
- Runtime은 지원하지 않는 MAJOR를 거부한다.
- 호환 가능한 선택 필드 추가는 MINOR, 기존 의미 변경은 MAJOR를 올린다.
- `revision`은 Draft 편집 충돌 탐지용이며 Published Version 번호와 다르다.

### Supported major matrix

| Consumer | GameProject major | Preview protocol major | Unknown major policy |
|---|:---:|:---:|---|
| Studio editor | 1 | 1 | read-only 안내 또는 load 거부 |
| Preview Runtime | 1 | 1 | `GAME_SCHEMA_UNSUPPORTED` |
| Published Web Runtime | 1 | N/A | `GAME_SCHEMA_UNSUPPORTED` |
| Spring Draft validator | 1 | N/A | 저장/Publish 거부 |
| Unity | N/A | N/A | GameProject를 소비하지 않음 |

### Migration policy

1. 기존 Published Version JSON을 제자리 수정하지 않는다.
2. MAJOR migration은 원본 snapshot을 입력으로 새 Draft 또는 새 Published Version을 만든다.
3. migration은 결정적이고 반복 실행 가능해야 하며 `fromVersion`, `toVersion`, 결과 validation을 기록한다.
4. Runtime은 알 수 없는 field를 추측하거나 unknown MAJOR를 best-effort 실행하지 않는다.
5. 호환 가능한 optional field 추가는 MINOR, 설명·문서·fixture 수정은 PATCH로 관리한다.
6. 지원 major 제거는 사용 중인 Published Version 수를 확인하고 변환·rollback 계획을 승인한 뒤 수행한다.

## Scene/Component 적용 판단

- `TOP_DOWN`과 후속 `PLATFORMER`만 이동·물리 Runtime 유형으로 본다.
- `DIALOGUE`는 이동 물리가 없는 Node/Choice 그래프다. `OVERLAY`는 호출한 이동 Scene을 보존하고,
  `FULL_SCREEN`은 시작/전환 대상 Scene으로 실행한다.
- `PUZZLE`은 v1의 별도 Scene 유형으로 만들지 않는다. 변수·아이템·Event·Object Component 조합으로 먼저 검증하고, 조합만으로 표현할 수 없는 퍼즐이 반복해서 확인될 때 확장한다.
- `preset`은 제작 UI의 빠른 시작값이고, 실행 능력은 허용 목록의 typed `components`와 `events`가 결정한다.
