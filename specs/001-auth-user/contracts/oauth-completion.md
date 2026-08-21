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

## 프론트엔드 의무

- 모든 완료 호출에 `credentials: 'include'`를 사용한다.
- `NICKNAME_REQUIRED`일 때만 닉네임 입력 UI를 연다.
- `400` handoff cookie 누락 또는 `410` handoff 만료·재사용 시 로그인 선택 화면으로 이동해 OAuth를 처음부터 재시작한다.
- Access Token은 응답 본문에서만 받아 메모리에 보관한다. Refresh Token 또는 `oauth_handoff` cookie를 읽거나 저장하지 않는다.
