# Research: 슬롯 연속 낙첨 보장

## Decision: 기존 원장에서 연속 낙첨을 계산한다

- **Decision**: 최신 `SLOT_BET`을 최대 10건 역순으로 읽고, 같은 `referenceId`의 `SLOT_PAYOUT` 존재로
  당첨 여부를 판정한다.
- **Rationale**: 스핀의 회원·순서·금액·당첨 사실이 이미 원장에 있어 상태를 중복 저장하지 않는다.
- **Alternatives considered**: pity 카운터 테이블은 원장과 불일치할 수 있어 배제했고, Redis 카운터는
  영속 경제 판정에 복구·TTL 정책을 추가하므로 배제했다.

## Decision: 지갑 잠금 전에 읽지 않는다

- **Decision**: `WalletService.lockOwner(userId)` 뒤에 원장을 조회하고, 같은 트랜잭션에서 결과를 정산한다.
- **Rationale**: 10회 연속 낙첨을 동시에 읽은 두 요청이 둘 다 보장 당첨이 되는 check-then-act 경쟁을 막는다.
- **Alternatives considered**: 원장 unique key만으로는 서로 다른 새 spinId의 두 요청을 구분하지 못하므로 배제했다.

## Decision: tier 3의 의미만 ×10으로 갱신한다

- **Decision**: tier 수와 API 응답은 유지하고 설정의 세 번째 승리 구간 multiplier만 10으로 바꾼다.
- **Rationale**: Unity는 tier 0..3과 세 승리 연출을 이미 지원하므로 consumer 변경 없이 계약을 지킨다.
- **Alternatives considered**: tier 4 추가는 Unity가 값을 자르므로 배제했다.
