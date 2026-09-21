// @vitest-environment jsdom
// 전체화면 배선 (S15P21A604-733) — 진입 경로는 HUD 토글 하나다.
//
// 월드 입장에서 자동으로 걸지 않는다. 요청하지 않은 전체화면은 화면이 갑자기 바뀌는 것으로만 보인다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { WorldHud } from '../../ui/WorldHud';
import type { UnityInstance, UnityProgressListener } from '../../../../unity/host/types';

const boots: { resolve: (i: UnityInstance) => void }[] = [];
vi.mock('../../../../unity/host/sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});
vi.mock('../../../../unity/host/authBridge', () => ({ syncAccessToken: () => 'cleared' }));
vi.mock('../../../../unity/host/audioBridge', () => ({ syncAudioMute: () => {}, syncAudioVolume: () => {} }));
vi.mock('../../../../unity/host/inputBridge', () => ({ syncInputLock: () => {} }));

let request: ReturnType<typeof vi.fn>;

beforeEach(() => {
  boots.length = 0;
  window.sessionStorage.clear();
  request = vi.fn(() => Promise.resolve());
  Object.defineProperty(document.documentElement, 'requestFullscreen', { value: request, configurable: true });
  Object.defineProperty(document, 'fullscreenElement', { value: null, configurable: true, writable: true });
});

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
});

describe('HUD 전체화면 토글 (-733)', () => {
  function toggle() {
    return screen.getByRole('button', { name: /전체화면/ });
  }

  it('우상단에 상시 있다 — 나갈 방법이 화면에 없으면 안 된다', () => {
    render(<WorldHud />);
    expect(toggle()).not.toBeNull();
  });

  it('누르면 전체화면을 요청한다', () => {
    render(<WorldHud />);
    fireEvent.click(toggle());
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('아이콘 상태는 브라우저를 따라간다 — FE state 가 아니다', () => {
    render(<WorldHud />);
    expect(toggle().getAttribute('aria-pressed')).toBe('false');
    Object.defineProperty(document, 'fullscreenElement', { value: document.body, configurable: true });
    act(() => { document.dispatchEvent(new Event('fullscreenchange')); });
    expect(toggle().getAttribute('aria-pressed')).toBe('true');
  });
});

describe('자동 진입 없음 (-733)', () => {
  async function bootHost() {
    const { UnityHost } = await import('../../../../unity/host/UnityHost');
    render(<UnityHost />);
    await act(async () => {
      boots[0].resolve({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
    });
  }

  it('월드 입장 신호는 전체화면을 부르지 않는다', async () => {
    await bootHost();
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    expect(request).not.toHaveBeenCalled();
  });
});
