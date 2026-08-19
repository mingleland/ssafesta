# SSAFESTA Backend Bruno 사용법

이 폴더를 Bruno에서 **Open Collection**으로 열고, 좌측 상단 Environment를 `local`로 선택한다.

`baseUrl`은 백엔드 API 주소(`http://localhost:8080`), `frontendOrigin`은 Vite 프론트 주소(`http://localhost:5173`)다. 다른 서버를 쓸 때만 값을 바꾼다. 실제 Access Token, Refresh Token, OAuth handoff, OAuth key는 이 파일이나 환경 파일에 커밋하지 않는다.

## 폴더 구성

| 폴더 | 용도 | 실행 전 필요한 값 |
|---|---|---|
| `01-auth` | 게스트·소셜 OAuth·refresh·logout | OAuth 완료는 임시 handoff 필요 |
| `02-users` | 내 정보·닉네임·탈퇴 | 회원 Access Token |
| 이후 `03-world`, `04-booth`, `05-wallet` | 도메인 구현 시 추가 | 각 도메인별 값 |
| `06-admin` | 관리자 role·권한 모델 확정 후 추가 | 현재 추후 작업 |

## 회원 API 테스트 순서

### 1. OAuth 로그인을 브라우저에서 시작

`구글 로그인`, `카카오 로그인` 요청은 URL 확인용이다. Google/Kakao 화면을 거치는 OAuth는 **일반 브라우저**에서 원하는 제공자의 주소를 열어 진행한다.

```text
http://localhost:8080/api/v1/auth/oauth/google
http://localhost:8080/api/v1/auth/oauth/kakao
```

로그인 뒤 Vite 프론트의 `http://localhost:5173/auth/callback`으로 이동한다. callback 화면은 `POST /api/v1/auth/oauth/complete`를 `credentials: 'include'`로 호출해 기존/신규 회원을 분기한다.

프론트 로그인 버튼은 OAuth 시작 API를 fetch/axios로 호출하지 않고 다음처럼 페이지를 이동해야 한다.

```javascript
window.location.href = `${API_BASE_URL}/api/v1/auth/oauth/google`;
// Kakao: `${API_BASE_URL}/api/v1/auth/oauth/kakao`
```

### 2. OAuth handoff를 Bruno에 임시 입력

브라우저 개발자 도구의 Cookies에서 `oauth_handoff` 값을 복사해 Bruno `local` 환경의 `oauthHandoff`에 넣는다.

- 5분 후 만료된다.
- 완료 요청을 한 번 실행하면 즉시 폐기된다.
- 실제 프론트에서는 HttpOnly cookie가 자동 전송되므로 값을 읽거나 저장하지 않는다. 이것은 Bruno 로컬 검증 전용 절차다.

### 3. 기존/신규 회원 판별

`03-oauth-complete-existing-member`를 body 그대로 `{}`로 실행한다.

| 응답 | 다음 행동 |
|---|---|
| `200`, `status: AUTHENTICATED` | 기존 회원 로그인 성공. request script가 `accessToken` 환경변수에 Access Token을 자동 저장한다. |
| `200`, `status: NICKNAME_REQUIRED` | 신규 회원이다. 아래 4단계로 진행한다. |
| `400` | `oauthHandoff`가 없거나 Bruno header에 넣지 않은 상태다. |
| `410` | handoff가 만료·소비됐다. OAuth를 브라우저에서 다시 시작한다. |

### 4. 신규 회원 닉네임 완료

`local` 환경의 `nickname`을 원하는 닉네임으로 바꾼 뒤 `04-oauth-complete-new-member`를 실행한다.

성공하면 `AUTHENTICATED` 응답이 오고 Access Token이 자동 저장된다. 첫 `{}` 요청에서 `NICKNAME_REQUIRED`가 나온 경우에만 이 요청을 실행한다.

### 5. 보호 API 실행

`02-users/01-get-my-account`를 실행한다. 이 요청과 이후 회원 보호 API는 `{{accessToken}}`을 `Authorization: Bearer` 헤더로 자동 전송한다.

```text
02-users/01-get-my-account
→ 02-users/02-change-nickname
→ 01-auth/05-refresh
→ 01-auth/06-logout
```

`05-refresh`는 Bruno cookie jar에 저장된 `refresh_token`을 자동 전송하고, 새 Access Token을 `accessToken` 환경변수에 덮어쓴다. Refresh Token을 body나 환경변수에 수동으로 넣지 않는다.

## 각 요청 설명

| 요청 | 하는 일 | 성공 시 |
|---|---|---|
| `게스트 로그인` | 비회원 관람용 토큰 발급 | Access Token 저장. `02-users` API는 이용 불가. |
| `구글 로그인` | Google OAuth 시작 주소 확인 | 브라우저에서 사용. |
| `카카오 로그인` | Kakao OAuth 시작 주소 확인 | 브라우저에서 사용. |
| `OAuth complete - existing member` | 기존 회원 로그인 또는 신규 회원 판별 | `AUTHENTICATED` 또는 `NICKNAME_REQUIRED` |
| `OAuth complete - new member nickname` | 신규 회원 닉네임 가입 완료 | `AUTHENTICATED`, Access Token 저장 |
| `Refresh access token` | Refresh cookie로 Access Token 교체 | 새 Access Token 저장, Refresh Token rotation |
| `Logout` | 현재 회원 세션·Refresh cookie 폐기 | `204 No Content`, Access Token 환경값 삭제 |
| `Get my account` | 내 닉네임·상태·연결 제공자 조회 | 회원 정보 JSON |
| `Change nickname` | 환경의 `nickname` 값으로 변경 | 변경된 회원 정보 JSON |
| `Withdraw account - confirm manually` | 즉시 hard delete 탈퇴 | 기본값은 안전하게 `confirmed: false` |

## 주의

- 탈퇴 테스트는 폐기 가능한 소셜 계정으로만 한다. 실제 실행하려면 request body의 `confirmed`를 `true`로 바꿔야 하며, 계정과 연결 데이터가 즉시 삭제된다.
- 게스트 Access Token은 공개 관람 전용이다. 내 정보·닉네임·탈퇴 API에는 사용할 수 없다.
- 새 OAuth 로그인이나 Refresh 성공 뒤에는 이전 Access Token이 무효화될 수 있으므로, Bruno 환경변수에 저장된 최신 `accessToken`을 사용한다.
