# Contract (제안): 아바타 프로필 저장 API

**Spec**: 013 | **당사자**: Unity ↔ Spring
**상태**: ✅ **구현 완료** (BE, 2026-08-24 — spec 013a `BE/tasks.md` T001~T014, 회귀 232/232). Unity는 Mock을 실제 호출로 교체할 수 있다.

---

## 확정된 형태 (구현 기준)

아래 "제안 형태"의 두 갈래 중 **무엇을 골랐는지**를 먼저 적는다. 근거는 `BE/research.md` R-01~R-08.

| 항목 | 확정 | 근거 |
|---|---|---|
| 메서드 | **`PUT`** | 본문이 값 전체 교체다. `MockUserApiClient.cs:23`의 `PATCH` 주석은 #24 확정 **이전** POC C 시점 값이라 무효 — 정정 요청함 (R-01) |
| 조회 경로 | **`GET /api/v1/users/me` 응답에 `avatarCode` 포함.** 별도 `GET /users/me/avatar`는 **만들지 않았다** | Unity `UserProfileDto.avatarCode`(`Dtos.cs:11`)가 이미 그 모양으로 대기 중이라 소비자가 그쪽이다. 404 분기 없이 `null` 하나로 끝난다 (R-02) |
| 저장 전 값 | **`null`** (키는 존재, 값만 비어 있음) | 서버가 기본 프리셋을 만들어 넣지 않는다 — 폴백은 클라이언트 몫(FR-010) |
| 길이 상한 | **3800자** | Unity `AvatarAppearance.MaxEncodedLength`와 동일. **하향 금지** (R-04, 헌법 23조) |
| 문자셋 | **인쇄 가능 ASCII `0x20`–`0x7E`** | 인코더 알파벳에서 유도. 더 좁히면 파츠 이름의 `-`·공백이 거부된다 (R-03) — ⚠️ **Unity 확인 요청 중** |
| 오류 봉투 | `400 VALIDATION_FAILED` + `errors[0] = { rule: "FIELD_INVALID", field: "avatarCode", message }` | #58 C 확정 봉투 재사용. 새 code·rule 0개 (R-07) |
| 게스트 | `403 MEMBER_ONLY` | 헌법 12조. 클라이언트가 호출을 건너뛰는 것과 별개로 서버가 막는다(헌법 16조) |

**왕복 무손실**: 저장한 문자열이 그대로 돌아온다. trim·대소문자·정규화를 하지 않으며 통합 테스트로 고정했다(`MyAccountAvatarApiIntegrationTest`).

<details>
<summary>구현 전 제안 원문 (2026-08-21까지)</summary>

Backend 아바타 API가 아직 없다. 존재하지 않는 엔드포인트를 가정해 배선하면
BE 구현 시점에 형식이 어긋나고 그때까지 실패 로그만 쌓인다.
따라서 Unity는 `IAvatarProfileStore` 뒤에서 **로컬 저장으로 먼저 동작**시키고,
BE가 준비되면 구현체만 교체한다.

</details>

---

## 제안 형태

### 조회

```http
GET /api/v1/users/me
→ 200 { "userId": 1, "nickname": "…", "status": "ACTIVE", "providers": [...],
        "avatarCode": "<직렬화 문자열>" | null }
```

> 별도 `GET /users/me/avatar`는 **구현하지 않았다.** 소비자가 프로필 조회 쪽이다(위 확정 표).

### 저장

```http
PUT /api/v1/users/me/avatar
{ "avatarCode": "<직렬화 문자열>" }

→ 200 { "avatarCode": "<직렬화 문자열>" }          # 저장한 그대로 echo
→ 400 VALIDATION_FAILED                          # 빈 값 · 3801자 이상 · 비인쇄 문자
       errors[0] = { rule: "FIELD_INVALID", field: "avatarCode", message: <사유> }
→ 401 미인증
→ 403 MEMBER_ONLY                                # 게스트
```

거부 사유 세 가지는 **서로 다른 문장**을 받는다 — 무엇이 틀렸는지 사용자가 알 수 있어야 한다(FR-012·SC-005, T-24).

> **필드명 확정 — `avatarCode`** (2026-08-21, #24 통보). DB `avatar_code` · JPA `avatarCode` · Unity `AvatarCode`와 한 이름이다.

---

## BE에 필요한 것 — ⚠️ 중요

| 항목 | 값 |
|---|---|
| 저장 컬럼 타입 | **`TEXT`** — ✅ `V10__avatar_code_text.sql`로 완료 |
| **`VARCHAR(32)` 금지** | 구 계약서의 "avatarCode 29~32자"는 **무효**다 |
| 검증 상한 | **3800자** — DB 제약이 아니라 요청 검증으로만 건다. 상한의 소유자가 Unity 인코더라 스키마에 박으면 변경마다 마이그레이션이 된다 |

구 계약을 그대로 믿고 `VARCHAR(32)`로 만들면, 항목이 늘어난 시점에 저장이 잘리거나 실패한다.
**과거에 정확히 이 형태의 사고가 있었다** (T-24 — 길이 초과가 조용히 잘려 무반응).

## 서버 검증

- 길이 상한(**3800**)과 허용 문자셋(**인쇄 가능 ASCII `0x20`–`0x7E`**)만 검증한다 — `AvatarCodePolicy`
- **문자열 내용을 파싱하지 않는다** — 항목 해석은 클라이언트 책임이다. trim·대소문자·정규화도 하지 않는다
- 항목 소유권 검증(미구매 항목 차단)은 상점 도입 시(spec 012) 이 지점에 추가한다

## 게스트

게스트 사용자의 외형은 **저장하지 않는다** (헌법 12조).
Unity가 게스트 여부를 판단해 저장 호출 자체를 하지 않는다.

## 실패 시 동작

저장이 실패해도 **월드 이용과 이번 세션의 외형은 유지된다** (FR-014).
저장 실패가 사용자를 막지 않는다.
