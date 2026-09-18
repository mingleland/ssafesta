// Idempotency-Key 발급 (S15P21A604-828 → -842로 공용화).
//
// 서버에 멱등 처리를 맡기는 모든 곳(코인 조정, 이벤트 상점 구매/응모 등)이 같은 규칙을 따른다:
// 키는 "시도 한 건"의 이름이지 "요청 한 번"의 이름이 아니다. 실패 후 재시도는 같은 키를 다시
// 보내야 서버가 첫 결과를 그대로 돌려준다. 성공했을 때만 키를 버리고 다음 시도부터 새로 만든다.
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // secure context가 아니면 crypto.randomUUID가 없다(예: http://192.168.x.x:5173 dev 접속) — 서버는 UUID 형식만 받는다
  const hex = (n: number) => Math.floor(Math.random() * 16 ** n).toString(16).padStart(n, '0');
  return `${hex(8)}-${hex(4)}-4${hex(3)}-a${hex(3)}-${hex(12)}`;
}
