// getCurrentOverlay의 useSyncExternalStore snapshot 안정성 — openOverlay/closeOverlay로만
// current가 재할당되고, 그 사이에는 같은 참조를 반환해야 한다(안 그러면 무한 리렌더).
import { beforeEach, describe, expect, it } from 'vitest';
import { closeOverlay, getCurrentOverlay, openOverlay, subscribeOverlay } from '../../overlay.ts';

describe('overlay bus — snapshot 안정성', () => {
  beforeEach(() => {
    closeOverlay();
  });

  it('변경 없이 반복 호출하면 같은 참조를 반환한다', () => {
    openOverlay('LAPTOP', { boothId: 7 });
    const a = getCurrentOverlay();
    const b = getCurrentOverlay();
    expect(a).toBe(b);
  });

  it('openOverlay는 새 참조로 전이하고, 구독자에게 알린다', () => {
    let notified = 0;
    const unsubscribe = subscribeOverlay(() => {
      notified += 1;
    });

    openOverlay('LAPTOP', { boothId: 7 });
    const first = getCurrentOverlay();
    openOverlay('AI_CHAT', { boothId: 7, agentId: 1 });
    const second = getCurrentOverlay();

    expect(first).not.toBe(second);
    expect(second).toEqual({ type: 'AI_CHAT', payload: { boothId: 7, agentId: 1 } });
    expect(notified).toBe(2);
    unsubscribe();
  });

  it('closeOverlay는 null로 전이한다', () => {
    openOverlay('LAPTOP', { boothId: 7 });
    closeOverlay();
    expect(getCurrentOverlay()).toBeNull();
  });
});
