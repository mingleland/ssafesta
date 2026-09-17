# Quickstart: 슬롯 확률·연속 낙첨 보장 검증

## Prerequisites

- Docker/Testcontainers를 실행할 수 있어야 한다.
- `backend` 디렉터리에서 Maven wrapper를 사용한다.

## Focused verification

```powershell
cd backend
.\mvnw.cmd -B "-Dtest=SlotMachineOddsTest,SlotMachineSpinApiIntegrationTest" test
```

확인할 결과:

1. 확률 설정의 기본 RTP가 0.52이고 tier 3 payout이 ×10이다.
2. 원장에 연속 `SLOT_BET` 10개만 있으면 다음 API 스핀이 tier 1, 20 Coin으로 정산된다.
3. `SLOT_PAYOUT`은 승리 스핀에만 있고, 지갑 잔액과 원장 합계가 일치한다.

## Full regression

```powershell
cd backend
.\mvnw.cmd -B clean test
```
