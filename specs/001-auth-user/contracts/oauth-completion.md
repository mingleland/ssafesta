# OAuth 완료 API 계약

## 브라우저 흐름

```text
GET /api/v1/auth/oauth/google|kakao
  → provider 인증
  → backend callback
  → Set-Cookie: oauth_handoff (HttpOnly, Secure, SameSite=Lax, Path=/api/v1/auth/oauth/complete, 5분)
  → 302 {FRONTEND_BASE_URL}/auth/callback
  → POST /api/v1/auth/oauth/complete (credentials: 'include')
```

`ticket` query parameter를 읽거나 전송하지 않는다. 기존 회원과 최초 회원 모두 `/auth/callback`을 사용한다.

## `POST /api/v1/auth/oauth/complete`

Request body는 선택 사항이다.

```json
{ "nickname": "선택한닉네임" }
```

첫 호출 결과:

```json
{ "status": "AUTHENTICATED", "accessToken": "...", "expiresAt": "..." }
```

- 기존 회원: 위 응답을 즉시 반환하고 `oauth_handoff`를 소비한다.
- 최초 회원(닉네임 누락): 아래 응답을 반환한다. handoff는 소비하지 않는다.

```json
{ "status": "NICKNAME_REQUIRED", "accessToken": null, "expiresAt": null }
```

프론트는 닉네임 입력 UI를 표시하고 같은 endpoint에 `{ "nickname": "..." }`를 다시 호출한다. 성공 시 `AUTHENTICATED`가 반환된다. 두 성공 경로 모두 응답 본문에 Access Token, `refresh_token` HttpOnly cookie를 포함한다.

## 오류 응답 (2026-08-27 신설 — #113 훑기)

전부 공용 오류 봉투(`docs/08` §1.3)를 쓴다.

| 상황 | status | code |
|---|---|---|
| handoff cookie 누락 | 400 | `OAUTH_HANDOFF_MISSING` |
| handoff 만료·재사용·미발급 | 410 | `OAUTH_HANDOFF_EXPIRED` |
| Origin 불일치 | 403 | `UNTRUSTED_ORIGIN` |
| 닉네임 형식·금칙 위반 | 400 | `NICKNAME_INVALID` |
| **닉네임 중복** | **409** | **`NICKNAME_DUPLICATED`** |
| **가입 경합** (중복 검사와 INSERT 사이) | **409** | **`REGISTRATION_CONFLICT`** |

- 표가 없던 동안 아래 두 행은 **구현이 500** 이었다. `DuplicateNicknameException`·`RegistrationConflictException`
  이 코드를 싣지 않은 맨 `RuntimeException` 이라 `handleUnexpected` 로 떨어졌다. 닉네임을 고르던 사람은
  "이미 사용 중입니다" 대신 "서버 오류가 발생했습니다"를 봤다. 계약 변경이 아니라 공백을 메운 것이다.
- 두 409를 **한 코드로 합치지 않는다.** 요구하는 행동이 다르다 — 중복은 "다른 닉네임을 고르라",
  경합은 "같은 요청을 다시 보내라"다.

## 정지 계정 redirect (2026-08-27 신설)

정지된(`SUSPENDED`) 계정이 소셜 인증에 성공하면 세션을 발급하지 않고(FR-021) 프론트로 돌려보낸다.

```text
302 {FRONTEND_BASE_URL}/auth/callback?error=ACCOUNT_SUSPENDED
Set-Cookie: oauth_handoff=; Max-Age=0; Path=/api/v1/auth/oauth/complete
```

- **잔존 handoff 를 반드시 지운다.** handoff 는 최대 5분 살아 있고 프론트는 `/auth/callback` 도착마다
  `complete` 를 무조건 호출하므로, 이전 시도의 쿠키가 남아 있으면 거부된 방문자가 그것을 타고 세션을 얻는다.
  삭제 쿠키는 원본과 같은 `Path`·속성이어야 브라우저가 지운다.
- `ACCOUNT_SUSPENDED` 는 `ErrorCode` enum에 **없다.** JSON 봉투가 아니라 브라우저 navigation 채널의
  어휘이고, 이 문서가 소유한다.
- **FR-021b 위반이 아니다.** 그 조항이 URL 노출을 금지하는 대상은 **handoff 식별자**다.
- ⚠️ **이 계약은 전달까지만이다.** 프론트가 아직 이 파라미터를 읽지 않으므로, 지금 정지 회원이 보는 것은
  재시작 화면("로그인 정보가 만료되었습니다")이다 — 사실과 다른 안내다. **spec 001 AS-5**(상태와 가능한
  후속 행동 안내)는 프론트가 이 값을 소비할 때 충족되며, 그 결정은 `docs/26`에 올라가 있다.

## 프론트엔드 의무

- 모든 완료 호출에 `credentials: 'include'`를 사용한다.
- `NICKNAME_REQUIRED`일 때만 닉네임 입력 UI를 연다.
- `400` handoff cookie 누락 또는 `410` handoff 만료·재사용 시 로그인 선택 화면으로 이동해 OAuth를 처음부터 재시작한다.
- Access Token은 응답 본문에서만 받아 메모리에 보관한다. Refresh Token 또는 `oauth_handoff` cookie를 읽거나 저장하지 않는다.
