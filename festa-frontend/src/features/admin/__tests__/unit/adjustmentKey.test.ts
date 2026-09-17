import { describe, expect, it } from 'vitest';
import { createAdjustmentDraft, draftProblem, editDraft, newIdempotencyKey } from '../../model/adjustmentKey';

describe('멱등키 수명', () => {
  it('키는 UUID 이고 draft 를 편집해도 유지된다 — 재시도는 같은 키다', () => {
    const draft = createAdjustmentDraft();
    expect(draft.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    const edited = editDraft(editDraft(draft, { signedAmount: 100 }), { note: '보정' });
    expect(edited.key).toBe(draft.key);
    expect(edited).toMatchObject({ signedAmount: 100, note: '보정' });
  });

  it('새 조정만 새 키다', () => {
    expect(createAdjustmentDraft().key).not.toBe(createAdjustmentDraft().key);
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });

  it('0·소수·500자 초과 사유는 보낼 수 없다', () => {
    const d = createAdjustmentDraft();
    expect(draftProblem(d)).toMatch(/0일 수 없/);
    expect(draftProblem(editDraft(d, { signedAmount: 1.5 }))).toMatch(/정수/);
    expect(draftProblem(editDraft(d, { signedAmount: 10, note: 'x'.repeat(501) }))).toMatch(/500자/);
    expect(draftProblem(editDraft(d, { signedAmount: -10 }))).toBeNull();
  });
});

