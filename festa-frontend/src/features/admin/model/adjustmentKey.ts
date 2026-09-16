// 코인 조정의 멱등키 수명 (S15P21A604-828).
//
// `Idempotency-Key` 는 **조정 한 건의 이름**이지 요청 한 번의 이름이 아니다. 폼을 열 때 하나를 만들어
// 붙들고, 실패 후 재시도는 같은 값을 다시 보낸다 — 서버가 alreadyApplied 로 첫 결과를 돌려준다.
// 클릭마다 새로 만들면 재시도가 아니라 새 조정이 되어 **두 번 지급**된다. 새 조정을 시작할 때만 새 키다.
export interface AdjustmentDraft {
  key: string;
  signedAmount: number;
  note: string;
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // 매우 오래된 런타임 폴백 — 서버는 UUID 형식만 받는다
  const hex = (n: number) => Math.floor(Math.random() * 16 ** n).toString(16).padStart(n, '0');
  return `${hex(8)}-${hex(4)}-4${hex(3)}-a${hex(3)}-${hex(12)}`;
}

export function createAdjustmentDraft(): AdjustmentDraft {
  return { key: newIdempotencyKey(), signedAmount: 0, note: '' };
}

/** 입력만 바꾼다. 키는 그대로 — 아직 같은 조정이다 */
export function editDraft(draft: AdjustmentDraft, patch: Partial<Omit<AdjustmentDraft, 'key'>>): AdjustmentDraft {
  return { ...draft, ...patch };
}

/** 금액이 0 이거나 정수가 아니면 보낼 수 없다 (서버 VALIDATION_FAILED 와 같은 규칙) */
export function draftProblem(draft: AdjustmentDraft): string | null {
  if (!Number.isInteger(draft.signedAmount)) return '금액은 정수여야 합니다.';
  if (draft.signedAmount === 0) return '조정 금액은 0일 수 없습니다.';
  if (draft.note.length > 500) return '사유는 500자 이하여야 합니다.';
  return null;
}
