# Quickstart: AI 문서 처리 계약 검증

## 1. 준비

- Spring Business DB에 V21을 적용한다.
- FastAPI 환경에 Business/AI DB URL이나 DB credential이 없음을 확인한다.
- 방향별 내부 Service Token은 Secret으로 주입한다.

## 2. 정상 처리

1. Spring이 Document와 Job(`QUEUED`)을 먼저 저장한다.
2. `jobId + attemptNo + snapshot`을 FastAPI에 보내고 `202`를 확인한다.
3. FastAPI가 30초 heartbeat와 최대 200 Chunk/8 MiB 배치를 전송한다.
4. finalize 후 기존 Chunk 교체, Job=`SUCCEEDED`, Document=`READY`, 새 Chunk=`searchable=true`가 한 번에 반영되는지 확인한다.

## 3. 멱등·fencing·실패

- 같은 batch를 재전송해 결과가 변하지 않는지 확인한다.
- lease 만료 후 attempt를 증가시키고 이전 attempt 결과가 `409`인지 확인한다.
- 삭제·취소 후 결과가 `410`이며 상태를 되살리지 않는지 확인한다.
- 1·5·15분 최대 3회 재시도와 소진 시 Job=`DEAD`, Document=`FAILED`, staging=0을 확인한다.
- finalize 실패 시 기존 검색 Chunk가 유지되고 부분 결과가 노출되지 않아야 한다.

## 4. 검색 격리

`POST /internal/ai/chunk-search`로 다음을 검증한다.

- `boothId + agentId + searchable=true + READY`가 모두 강제된다.
- 다른 Booth/Agent, 비READY, 비검색 Chunk는 0건이다.
- `topK>20`은 거부되고 timeout은 3초다.
- 결과는 코사인 `distance` 오름차순이며 threshold로 탈락시키지 않는다.

## 5. 미결정

Agent 설정 API는 Jira S15P21A604-399에서 endpoint·DTO·호출 시점·캐시 정책이 합의되기 전 구현하지 않는다.
