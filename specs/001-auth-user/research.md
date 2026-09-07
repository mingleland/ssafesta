# Research: OAuth and Sessions

- Google/Kakao/SSAFY는 Authorization Code flow와 1회성 `state` 검증을 사용한다.
- Google은 OIDC nonce와 ID Token의 서명·issuer·audience·expiry를 검증하며 `sub`를 식별자로 사용한다.
- Kakao는 등록된 exact redirect URI와 client secret을 사용하고, 토큰 교환 뒤 provider subject를 검증한다.
- SSAFY는 인가 요청에 scope 파라미터가 없고, 토큰 교환은 body 에 client_id·client_secret 을 싣는다(`client_secret_post`).
  userInfo 응답은 `userId`·`email`·`name` 평면 구조이며 `userId`를 식별자로 사용한다.
- 식별자 속성명이 제공자마다 다르므로(`sub`·`id`·`userId`) 코드에서 분기하지 않고 각 registration 의
  `user-name-attribute` 에 적어 `principal.getName()` 하나로 읽는다. 분기하면 새로 추가한 제공자가
  조용히 `null` subject 를 받아 매 로그인이 신규 가입으로 흐른다.
- 회원별 Redis 활성 세션은 하나만 유지한다. 새 로그인은 기존 refresh family를 폐기하고 월드 퇴장 이벤트를 만든다.

Sources: Google OAuth web-server/OIDC docs, Kakao REST Login/security docs, SSAFY 로그인 API 명세(project.ssafy.com).
