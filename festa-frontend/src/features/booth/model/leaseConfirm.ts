// 임대 확인 모달의 잔액 판정 — 훅은 .tsx 테스트 공백(G-4)에 걸리므로 판정만 순수 함수로 뺀다.
// 출처: specs/004-booth-slot-lease/spec.md FR-013(변심 환불 없음) — 비가역 결제라 확인 단계가 있다.

// 'unknown'은 잔액을 아직 모르는 상태(로딩·조회 실패)다. 'insufficient'여도 요청 자체는 막지 않는다 —
// 서버가 일일 지급을 응답 전에 반영하므로 FE 캐시가 실잔액보다 낮을 수 있고, 조용한 차단은 T-24의 원인이다.
export type Affordability = 'unknown' | 'sufficient' | 'insufficient';

export function judgeAffordability(balance: number | undefined, cost: number): Affordability {
  if (balance === undefined) return 'unknown';
  return balance >= cost ? 'sufficient' : 'insufficient';
}
