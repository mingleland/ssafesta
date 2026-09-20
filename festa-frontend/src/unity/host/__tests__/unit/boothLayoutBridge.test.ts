// 부스 게시본 재조회 알림 (S15P21A604-644).
//
// 잠그는 것 둘. 계약 인자가 Unity 수신부(BoothLayoutBridge.ReloadBoothSlot — 문자열 슬롯 번호)와
// 정확히 같은가, 그리고 인스턴스가 없을 때 **큐 없이** 조용히 넘어가는가 — 다음 월드 진입이
// 어차피 최신을 읽으므로 보관할 이유가 없다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UnityInstance } from '../../types';

const ready = vi.fn<() => UnityInstance | null>(() => null);
vi.mock('../../sessionManager', () => ({ getReadyUnityInstance: () => ready() }));

const { notifyBoothSlotChanged, reloadBoothSlot } = await import('../../boothLayoutBridge');

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });

afterEach(() => { ready.mockReset(); ready.mockImplementation(() => null); });

describe('reloadBoothSlot', () => {
  it('Unity 수신부 계약 그대로 보낸다 — 오브젝트·메서드·문자열 슬롯 번호', () => {
    const inst = instance();
    reloadBoothSlot(inst, 6);
    expect(inst.SendMessage).toHaveBeenCalledTimes(1);
    expect(inst.SendMessage).toHaveBeenCalledWith('BoothLayoutBridge', 'ReloadBoothSlot', '6');
  });
});

describe('notifyBoothSlotChanged', () => {
  it('인스턴스가 떠 있으면 그 슬롯을 알리고 true', () => {
    const inst = instance();
    ready.mockImplementation(() => inst);
    expect(notifyBoothSlotChanged(6)).toBe(true);
    expect(inst.SendMessage).toHaveBeenCalledWith('BoothLayoutBridge', 'ReloadBoothSlot', '6');
  });

  it('인스턴스가 없으면 아무 일도 하지 않고 false — mock 월드·boot 전·월드 미진입', () => {
    expect(() => notifyBoothSlotChanged(6)).not.toThrow();
    expect(notifyBoothSlotChanged(6)).toBe(false);
  });

  it('없을 때 보관하지 않는다 — 나중에 인스턴스가 서도 지난 알림을 다시 보내지 않는다', () => {
    notifyBoothSlotChanged(6);
    const inst = instance();
    ready.mockImplementation(() => inst);
    expect(inst.SendMessage).not.toHaveBeenCalled();
  });
});
