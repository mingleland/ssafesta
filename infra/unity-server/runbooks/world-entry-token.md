# World entry token provisioning and rotation

## 서버 준비 전

- 저장소에는 `CONNECTION_TOKEN_SECRET`과 `CONNECTION_TOKEN_SECRET_FILE` 이름만 둔다.
- local/demo 기본키, 자동 생성 fallback 또는 커밋된 샘플키를 만들지 않는다.
- Backend와 game의 issuer/audience/world/channel 고정값을 `contracts/runtime-env.md`와 대조한다.

## 최초 주입

1. 승인된 Secret 저장소에서 무작위 32바이트 이상 값을 생성하고 Base64 문자열로 보관한다.
2. Base64 디코딩 결과가 32바이트 이상인지 값 노출 없이 검사한다.
3. Backend에는 `CONNECTION_TOKEN_SECRET`, game에는 같은 참조의 read-only Secret 파일을 주입한다.
4. Secret 파일은 game process user만 읽을 수 있게 하고 로그·Compose rendering·evidence에 내용을 남기지 않는다.
5. Backend가 발급한 미사용 grant 하나를 game이 승인하고, 변조·만료·재사용 grant를 거부하는지 확인한다.

## 회전

HS256은 issuer와 verifier가 같은 키를 가져야 한다. P0에는 다중 키 검증이 없으므로 무중단 회전을 약속하지 않는다.

1. 새 world-session 발급을 maintenance window 동안 차단한다.
2. 기존 120초 grant의 만료를 기다린다.
3. Backend와 game에 같은 새 Secret reference를 배포한다.
4. 양쪽 readiness와 정상/오류 grant 검증 후 발급을 재개한다.
5. 이전 Secret을 폐기하고 회전 시각과 검증 evidence reference만 기록한다.

## 장애 처리

- Secret 누락·Base64 오류·32바이트 미만: 두 서비스 모두 fail-fast, 기본키 사용 금지.
- replay ledger append/flush 실패: 입장 fail-closed.
- issuer/verifier 불일치: 발급을 중단하고 동일 Secret reference 여부를 확인한다. 토큰이나 Secret 원문을 수집하지 않는다.
- Backend 장애: 이미 발급된 미사용 grant는 game이 로컬 검증하되 새 발급은 실패한다.
