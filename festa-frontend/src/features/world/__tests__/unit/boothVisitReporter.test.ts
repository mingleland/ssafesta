// 부스 방문 발신 어댑터 (S15P21A604-690, GitLab #186).
//
// 경계 판정은 boothVisitTracker.test 가 본다. 여기서 보는 것은 **무엇이 서버로 나가고 무엇이
// 나가지 않는가** 하나다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  __resetBoothVisitTrackerForTests,
  applyWorldContextChange,
} from '../../model/boothVisitTracker';

const enterBooth = vi.fn();
const exitBooth = vi.fn();

vi.mock('../../../../entities/booth/visitApi', () => ({
  enterBooth: (...args: unknown[]) => enterBooth(...args),
  exitBooth: (...args: unknown[]) => exitBooth(...args),
}));

let stop: () => void;
let warn: ReturnType<typeof vi.spyOn>;

beforeEach(async () => {
  __resetBoothVisitTrackerForTests();
  __resetSessionForTests();
  enterBooth.mockReset().mockResolvedValue({ visitId: 'v-1', enteredAt: 'now' });
  exitBooth.mockReset().mockResolvedValue(undefined);
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const { startBoothVisitReporting } = await import('../../model/boothVisitReporter');
  stop = startBoothVisitReporting();
});

afterEach(() => {
  stop();
  warn.mockRestore();
});

const member = () => setMemberSession('at', '2026-12-31T00:00:00.000Z');
const guest = () => setGuestSession('at', '2026-12-31T00:00:00.000Z');
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

const inside = (boothId: number | null) => ({ insideBooth: true, boothId });
const outside = { insideBooth: false, boothId: null };

describe('부스 방문 발신 (-690)', () => {
  it('입장은 본문 없이 부스 번호로만 나간다', async () => {
    member();
    applyWorldContextChange(inside(7));
    await flush();

    expect(enterBooth).toHaveBeenCalledWith(7);
    expect(enterBooth).toHaveBeenCalledTimes(1);
  });

  it('퇴장은 입장 응답의 visitId 로 닫는다', async () => {
    member();
    applyWorldContextChange(inside(7));
    await flush();
    applyWorldContextChange(outside);
    await flush();

    expect(exitBooth).toHaveBeenCalledWith(7, 'v-1');
  });

  it('같은 상태가 반복돼도 입장을 두 번 보내지 않는다', async () => {
    member();
    applyWorldContextChange(inside(7));
    applyWorldContextChange(inside(7));
    await flush();

    expect(enterBooth).toHaveBeenCalledTimes(1);
  });

  it('게스트는 입장만 보내고 퇴장은 보내지 않는다 — 서버가 403 으로 막는다', async () => {
    guest();
    applyWorldContextChange(inside(7));
    await flush();
    applyWorldContextChange(outside);
    await flush();

    expect(enterBooth).toHaveBeenCalledWith(7);
    expect(exitBooth).not.toHaveBeenCalled();
  });

  it('부스 번호가 없으면 아무것도 보내지 않는다 — 경로에 넣을 값이 없다', async () => {
    member();
    applyWorldContextChange(inside(null));
    await flush();
    applyWorldContextChange(outside);
    await flush();

    expect(enterBooth).not.toHaveBeenCalled();
    expect(exitBooth).not.toHaveBeenCalled();
  });

  it('입장이 실패하면 퇴장도 보내지 않는다 — 서버에 닫을 행이 없다', async () => {
    member();
    enterBooth.mockRejectedValue({ code: 'BOOTH_LEASE_EXPIRED' });
    applyWorldContextChange(inside(7));
    await flush();
    applyWorldContextChange(outside);
    await flush();

    expect(exitBooth).not.toHaveBeenCalled();
  });

  it('발신이 실패해도 던지지 않는다 — 집계만 잃고 월드는 계속 간다', async () => {
    member();
    enterBooth.mockRejectedValue({ code: 'BOOTH_NOT_FOUND' });

    expect(() => applyWorldContextChange(inside(7))).not.toThrow();
    await flush();

    expect(warn).toHaveBeenCalled();
  });

  it('늦게 도착한 입장 응답이 그 사이 열린 새 방문에 붙지 않는다', async () => {
    member();
    let resolveFirst: (v: unknown) => void = () => {};
    enterBooth.mockImplementationOnce(
      () => new Promise((resolve) => { resolveFirst = resolve; }),
    );

    applyWorldContextChange(inside(7));
    applyWorldContextChange(outside);
    applyWorldContextChange(inside(8));
    await flush();

    // 첫 입장 응답이 이제야 온다 — 이미 다른 방문이 열려 있다
    resolveFirst({ visitId: 'stale', enteredAt: 'old' });
    await flush();

    applyWorldContextChange(outside);
    await flush();

    expect(exitBooth).not.toHaveBeenCalledWith(8, 'stale');
  });
});
