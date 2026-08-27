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
| 닉네임 형식·금칙 위반 | 400 | `NICKNAME_INVALID` |
| **닉네임 중복** | **409** | **`NICKNAME_DUPLICATED`** |
| **가입 경합** (중복 검사와 INSERT 사이) | **409** | **`REGISTRATION_CONFLICT`** |

- 표가 없던 동안 아래 두 행은 **구현이 500** 이었다. `DuplicateNicknameException`·`RegistrationConflictException`
  이 코드를 싣지 않은 맨 `RuntimeException` 이라 `handleUnexpected` 로 떨어졌다. 닉네임을 고르던 사람은
  "이미 사용 중입니다" 대신 "서버 오류가 발생했습니다"를 봤다. 계약 변경이 아니라 공백을 메운 것이다.
- 두 409를 **한 코드로 합치지 않는다.** 발견 시점이 다르다 — 중복은 사전 검사가, 경합은 DB 가 잡는다.
  서버는 **DB 가 이름을 댄 제약 둘**로만 경합을 판정한다 — `users_nickname_key` 와
  `oauth_identities_provider_provider_subject_key`. (`oauth_identities_user_id_provider_key` 는 방금 만든
  회원에 identity 를 하나 넣는 경로라 **발화할 수 없어 제외**한다 — 발화하면 서버 결함이다.)
  **그 밖의 무결성 위반은 이름을 아는 unique 든 아니든 전부 500 이다** — FK·NOT NULL·CHECK 는 물론이고,
  나중에 추가될 unique index 도 마찬가지다. 409 로 덮으면 서버 결함이 아무도 안 보는 상태에 묻히고
  사용자는 낫지 않을 재시도를 반복한다.
- **닉네임이 거부되면 handoff 는 살아 있다.** `NICKNAME_INVALID`·`NICKNAME_DUPLICATED` 는 handoff 를 건드리기
  전에 거절되므로 **같은 handoff 로 다른 닉네임을 다시 제출할 수 있다** — FR-021c 대로 **유효한 제출에만**
  소비한다. 프론트는 닉네임 입력 폼을 닫지 않아도 된다.
- **`REGISTRATION_CONFLICT` 는 다르다 — 재사용이 보장되지 않는다.** 이긴 쪽이 **누구의 handoff 를 썼는지**에
  달렸다. 다른 handoff 였다면 내 것은 살아 있어 재제출이 성공하지만, **같은 handoff 를 쥔 중복 제출**이었다면
  그쪽이 소비해 버려 재제출은 `410` 이다. 프론트는 이 코드에서 **재제출을 시도하되 `410` 도 받을 수 있다고
  보고**, 그때는 처음부터 재시작한다(FE 의무 3).
  > 2026-08-27 이전 구현은 닉네임을 **검사하기 전에** handoff 를 소비해서, 409 뒤의 재제출이 반드시
  > `410 OAUTH_HANDOFF_EXPIRED` 로 실패했다(실측: `409` → `410`). 409 가 "다른 닉네임을 고르라"고 안내하면서
  > 그 재시도를 불가능하게 만드는 막다른 길이었다 — !56 리뷰 지적으로 고쳤다.
- **그래도 handoff 하나는 세션 하나다.** 실패에는 살아남지만 **성공에는 정확히 한 번만 소비**된다. 같은
  handoff 를 쥔 요청이 여럿이면 **세션은 하나만 나간다** — 세션 발급은 그 계정의 **이전 세션을 폐기**하므로
  (단일 활성 세션) 두 번 발급하면 앞서 받은 쪽이 끊긴다.
  진 쪽의 코드는 **어디서 졌는지에 따라 다르다**: handoff 를 못 가져갔으면 `410`, 그 전에 등록 insert 에서
  졌으면 `409 REGISTRATION_CONFLICT` 다. 클라이언트가 알아야 할 것은 **세션을 받지 못했다**는 것이고, 둘 다
  위 표에 있는 코드다 — 하나로 단정하지 않는다.

### Origin — 이 endpoint 는 자체 검사를 하지 않는다

`/auth/refresh`·`/auth/logout` 과 달리 **`UNTRUSTED_ORIGIN` 을 던지지 않는다.** 다른 origin 에서의 호출은
컨트롤러에 닿기 전에 **CORS 계층이 403 으로 거절**하며, 그 응답은 **오류 봉투가 아니다**(`code` 없음).
허용 origin 은 `FRONTEND_BASE_URL` 하나이고 `allowCredentials=true` 다.

> 2026-08-27 최초 작성 시 이 자리에 `403 UNTRUSTED_ORIGIN` 행을 적었는데 **구현에 없는 계약이었다**
> (!56 리뷰 지적). `OAuthCompletionController` 에는 Origin 검사가 없다. 실동작은
> `OAuthCompletionApiIntegrationTest.aCallFromAnotherOriginIsRefusedOutsideTheEnvelope` 로 고정했다.

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

## 좁힌 판정 세 곳 — 미지 입력은 fail-closed 인가

서버가 "이건 클라이언트 잘못"이라고 판정하는 자리는 셋뿐이다. 셋 다 **모르는 입력을 아는 것으로 넘기지
않는다** — 판정에 실패하면 그 자리에서 가장 방어적인 답으로 떨어진다. 무엇이 방어적인지는 자리마다 다르다:
경합 판정은 **되던져 500** 이 되고(서버 결함을 409 에 묻지 않는다), 봉투 매핑은 **일반 4xx** 로 답하되
status 를 로그에 남기며, handoff 소비는 **세션을 주지 않는다.** 공통점은 "모르면 후하게 봐주지 않는다" 다.

| 판정 자리 | 예상 입력 | 예상 출력 | 미지 입력 | fail-closed |
|---|---|---|---|---|
| `GlobalExceptionHandler#handleUnexpected` 의 `ErrorResponse` 분기 | 프레임워크가 던진 4xx (404·405·400·415) | 그 status + 계약 code | 계약에 code 가 없는 status | ✅ 일반 code(`VALIDATION_FAILED`) — status 는 debug 로그에 남는다 |
| `RegistrationService#asSignupRaceOrRethrow` | `users_nickname_key` · `oauth_identities_provider_provider_subject_key` | 409 `NICKNAME_DUPLICATED` · 409 `REGISTRATION_CONFLICT` | 그 밖의 제약, 이름 없는 위반 | ✅ **그대로 되던짐 → 500 + `log.error`** |
| `OAuthCompletionController` 의 handoff 소비 | `discard` 가 `true` (이 호출이 소비함) | 세션 발급 | `discard` 가 `false` (남이 먼저 씀) | ✅ **410, 세션 미발급** |

5xx 는 어느 자리에서도 4xx 로 바뀌지 않는다. `handleUnexpected` 의 분기는 `is4xxClientError()` 일 때만 타므로,
프레임워크가 5xx 를 던지면 종전대로 `INTERNAL_ERROR` + `log.error` 다.

## 프론트엔드 의무

- 모든 완료 호출에 `credentials: 'include'`를 사용한다.
- `NICKNAME_REQUIRED`일 때만 닉네임 입력 UI를 연다.
- `400` handoff cookie 누락 또는 `410` handoff 만료·재사용 시 로그인 선택 화면으로 이동해 OAuth를 처음부터 재시작한다.
- Access Token은 응답 본문에서만 받아 메모리에 보관한다. Refresh Token 또는 `oauth_handoff` cookie를 읽거나 저장하지 않는다.
