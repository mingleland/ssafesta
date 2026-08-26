# Unity world deployment evidence

> Secret, connection token, JTI, nickname, Access Token, private key와 전체 환경 변수 출력을 기록하지 않는다.

## 실행 정보

- 실행 시각(UTC):
- 환경 / target: `demo` / `game`
- Infra release ID:
- Game image digest 또는 full commit SHA:
- Web client release ID:
- Backend release ID:
- 검증 담당자:

## 공개 경로

- DNS 대상 확인: PASS / FAIL / NOT RUN
- TLS chain·hostname·expiry: PASS / FAIL / NOT RUN
- WebSocket Upgrade: PASS / FAIL / NOT RUN
- public 7777 차단: PASS / FAIL / NOT RUN
- 승인된 WSS 입장: PASS / FAIL / NOT RUN
- 실패 계층: DNS / TLS / CLOUDFLARE / NGINX / LISTENER / APPROVAL / NONE

## P0 브라우저 검증

- 회원·게스트 동시 입장 및 상호 이동:
- 10분 무입력 유지:
- 종료 후 player/session 정리:
- 새 grant 수동 재접속 30초 이내:
- 비대상 서비스 restart delta:

## 수용량

| Clients | Duration | Accepted | Unexpected disconnects | CPU | Memory | Network | Other service restarts |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10m | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 10 | 10m | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 20 | 10m | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 30 | 10m | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 40 | 10m | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## 판정

- 결과: PASS / FAIL / BLOCKED
- current/known-good 변경 여부:
- 실패 evidence reference:
- 후속 조치:
