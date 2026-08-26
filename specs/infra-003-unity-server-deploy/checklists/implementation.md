# infra-003 implementation checklist

## 서버 없이 검증 가능

- [x] OpenAPI 요청에 optional `worldId=11F`가 있고 비지원 값의 400 오류 계약이 있다.
- [x] Backend와 game이 동일 Secret reference, issuer, audience, world와 channel 계약을 소비한다.
- [x] Compose는 7777을 host에 publish하지 않고 비관리자·cap drop·read-only Secret mount를 사용한다.
- [x] game replay ledger가 전용 persistent volume에 연결된다.
- [x] Nginx template이 TLS, Upgrade, no-store/no-cache와 초기 180초 timeout을 가진다.
- [x] 정적 검사와 Secret scan이 통과한다.

## 파트 통합 후 검증

- [ ] BE MR의 world-session API와 token issuer 테스트가 통과한다.
- [ ] Unity verifier·replay ledger·approval 테스트가 통과한다.
- [ ] 잘못된 worldId가 `400 VALIDATION_FAILED`와 `field=worldId`를 반환한다.
- [ ] Backend 발급 grant를 game이 같은 Secret으로 자체 검증한다.

## 서버 제공 후 검증

- [ ] 실제 Secret 주입과 fail-fast를 검증한다.
- [ ] DNS·Full(strict) TLS·외부 WSS와 public 7777 차단을 검증한다.
- [ ] 회원·게스트 브라우저 2개의 10분 idle 및 수동 재접속을 검증한다.
- [ ] game-only 배포에서 Backend·AI·web restart delta가 0이다.
- [ ] 1→10→20→30→40 수용량과 반복 성공 최대값을 기록한다.
- [ ] evidence에 Secret·token·개인정보 원문이 없다.
