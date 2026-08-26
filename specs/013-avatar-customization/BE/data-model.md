# Data Model: 013a 아바타 저장 API (BE분)

**Phase 1** | 2026-08-24 | 근거: [research.md](research.md)

## 1. 스키마 — 신규 마이그레이션 **0개**

필요한 저장소는 전부 이미 있다. 이 기능은 스키마를 만들지 않고 **기존 컬럼에 처음으로 쓰기 경로를 붙이는 것**이다.

| 대상 | 상태 | 근거 |
|---|---|---|
| `users.avatar_code TEXT NULL` | ✅ 존재 | V10(`avatar_code_text`)이 `VARCHAR` → `TEXT` 전환까지 마쳤다 (헌법 23조, T-24 예방) |
| JPA 매핑 `User.avatarCode` | ✅ 존재 | `User.java:27-28` — `@Column(name = "avatar_code", columnDefinition = "text")` |
| DB 제약 | **두지 않는다** | 길이·문자셋은 요청 검증(아래 §3)으로만 건다. `TEXT`에 CHECK를 붙이면 상한 변경이 마이그레이션이 된다 — 상한의 소유자는 Unity 인코더다(R-04) |

## 2. 엔티티 의미

### `User.avatarCode : String | null`

| 값 | 의미 |
|---|---|
| `null` | 외형을 한 번도 저장하지 않았다. **서버는 기본값을 만들어 넣지 않는다** — 프리셋 폴백은 클라이언트 책임이다 (FR-010·FR-015, 계약 "파싱하지 않는다") |
| 문자열 | Unity `AvatarAppearance.Encode()` 산출물. 서버에게는 **불투명**하다 — 내부 구조를 읽지도, 고치지도 않는다 (R-05) |

**불변식 — 왕복 무손실**: `PUT`으로 저장한 바이트열과 `GET /users/me`가 돌려주는 바이트열은 **동일**하다. 정규화·트림·대소문자 변경이 없다. (005 `layout_json` 원칙과 동일. 통합 테스트로 고정한다.)

## 3. 검증 규칙 (요청 게이트)

`AvatarCodePolicy.validate(String)` — 순서대로 검사하고 **첫 위반의 사유 문장**으로 400을 낸다 (FR-012: 무엇이 틀렸는지 보여준다).

| # | 규칙 | 위반 시 message |
|---|---|---|
| 1 | `null`·blank 금지 | `아바타 코드를 입력해 주세요.` |
| 2 | 길이 ≤ **3800** (R-04, Unity `MaxEncodedLength`와 동일 — **하향 금지**) | `아바타 코드가 너무 깁니다. (최대 3800자)` |
| 3 | 전 문자 인쇄 가능 ASCII `0x20`–`0x7E` (R-03) | `아바타 코드에 쓸 수 없는 문자가 있습니다.` |

세 경우 모두: `400 VALIDATION_FAILED` + `errors[0] = { rule: "FIELD_INVALID", field: "avatarCode", message }` (R-07).

## 4. 상태 전이

없다. 단일 nullable 필드의 전체 교체뿐이다 — `null → 값`, `값 → 다른 값`. 삭제(값 → null) 경로는 소비자가 없어 만들지 않는다(Unity는 항상 완전한 인코딩을 보낸다).

## 5. 동시성

낙관적 잠금을 **걸지 않는다.** 같은 계정의 마지막 저장이 이기는 last-write-wins로 충분하다 — 편집 주체가 본인 1명이고(부스 Layout처럼 협업 편집이 아니다), 잃는 것이 "방금 고른 외형" 하나라 revision 충돌 UI를 만들 대상이 아니다. 005가 revision을 둔 이유(여러 기기 편집 유실)와 대가가 다르다.
