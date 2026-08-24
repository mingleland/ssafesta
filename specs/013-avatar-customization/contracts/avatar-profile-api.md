# Contract: 아바타 프로필 저장 API

**Spec**: 013 | **당사자**: Unity ↔ Spring
**상태**: ✅ **구현 완료** (BE, 2026-08-24 — spec 013a `BE/tasks.md` T001~T019, 회귀 232/232). 파트 통보 [#76](https://github.com/kanghyunsoon/ssafesta/issues/76) 완료 — Unity는 Mock을 실제 호출로 교체할 수 있다.

---

## 확정된 형태 (구현 기준)

아래 "제안 형태"의 두 갈래 중 **무엇을 골랐는지**를 먼저 적는다. 근거는 `BE/research.md` R-01~R-08.

| 항목 | 확정 | 근거 |
|---|---|---|
| 메서드 | **`PUT`** | 본문이 값 전체 교체다. `MockUserApiClient.cs:23`의 `PATCH` 주석은 #24 확정 **이전** POC C 시점 값이라 무효 — ✅ `game` 브랜치는 이미 `PUT`(`9117199`)이고 develop·back·main 계열만 낡았다(#76). game→develop 반영 때 따라간다 (R-01) |
| 조회 경로 | **`GET /api/v1/users/me` 응답에 `avatarCode` 포함.** 별도 `GET /users/me/avatar`는 **만들지 않았다** | Unity `UserProfileDto.avatarCode`(`Dtos.cs:11`)가 이미 그 모양으로 대기 중이라 소비자가 그쪽이다. 404 분기 없이 `null` 하나로 끝난다 (R-02) |
| 저장 전 값 | **`null`** (키는 존재, 값만 비어 있음) | 서버가 기본 프리셋을 만들어 넣지 않는다 — 폴백은 클라이언트 몫(FR-010) |
| 길이 상한 | **3800자** | Unity `AvatarAppearance.MaxEncodedLength`와 동일. **하향 금지** (R-04, 헌법 23조) |
| 문자셋 | **인쇄 가능 ASCII `0x20`–`0x7E`** | 인코더 알파벳에서 유도. ✅ **Unity 확인 완료**(#76) — 영숫자·밑줄·구분자만 남기는 좁은 문자셋으로 갔다면 의상 색 36칸이 늘 내는 콤마와 알파 0을 뜻하는 하이픈 때문에 **정상 아바타가 전부 400**이었다. 비ASCII 경로는 현재 없으나 `rt` 파츠 모드(현재 호출자 0)가 살아나면 에셋명이 그대로 실리므로 그때 재검토한다 (R-03) |
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

## 인증

Unity가 Spring REST를 부를 때 실을 값이다. 새 결정이 아니라 2026-08-12 확정된 토큰 4계층(헌법 13조)의 적용이며 [#60](https://github.com/kanghyunsoon/ssafesta/issues/60)에서 확정했다(2026-08-23).

| 항목 | 값 |
|---|---|
| 헤더 | `Authorization: Bearer <access token>` — **Access Token 원본**을 그대로 쓴다. Unity 전용 토큰 계층을 새로 만들지 않는다 |
| 대상 | **MEMBER만.** 게스트는 `403 MEMBER_ONLY` (헌법 12조) |
| 쓸 수 없는 값 | **NGO Connection Token** — `POST /world-sessions` 응답의 1회용 값(TTL 60~120초)으로 게임 서버 접속 승인 전용이다(헌법 14조). REST 인증에 쓰면 두 번째 호출부터 실패한다 |
| Refresh Token | Unity 경계를 넘기지 않는다 (헌법 13조) |

전용 토큰을 만들지 않는 근거는 셋이다. ① WebGL은 React와 같은 페이지·같은 JS 힙이라 AT를 Unity로 넘기는 것이 **새 노출 표면을 만들지 않는다**(XSS 상황이면 React 쪽에서 이미 탈취된다). ② AT가 이미 30분 단수명이다. ③ AT의 `role`·`sid` 클레임으로 게스트 거부·세션 추적이 그대로 성립한다.

> **재검토 조건** — 위 ①은 "Unity가 브라우저 안에 있다"는 전제에 서 있다. 데스크톱·모바일 native 클라이언트가 생기면 토큰이 페이지 경계 밖으로 나가므로 그때는 전용 토큰이 맞을 수 있다. MVP는 WebGL 단독이다.

> **AT 만료 처리** — Unity가 AT를 캐시하면 30분 후 `401`을 받는다. "Unity는 `401`을 받으면 호스트(React)에 토큰을 재요청한다" 규약이 필요하고 **서버 쪽 추가 작업은 없다.** 전달 시점·방식·갱신 주체는 #60 범위 밖이다.

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
