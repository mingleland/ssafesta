# Research: OAuth and Sessions

- Google/Kakao는 Authorization Code flow와 1회성 `state` 검증을 사용한다.
- Google은 OIDC nonce와 ID Token의 서명·issuer·audience·expiry를 검증하며 `sub`를 식별자로 사용한다.
- Kakao는 등록된 exact redirect URI와 client secret을 사용하고, 토큰 교환 뒤 provider subject를 검증한다.
- 회원별 Redis 활성 세션은 하나만 유지한다. 새 로그인은 기존 refresh family를 폐기하고 월드 퇴장 이벤트를 만든다.

Sources: Google OAuth web-server/OIDC docs, Kakao REST Login/security docs.
