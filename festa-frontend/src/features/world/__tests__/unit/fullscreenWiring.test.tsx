// @vitest-environment jsdom
// 전체화면 배선 (S15P21A604-733) — 의도가 어디서 남고 어디서 쓰이는가.
//
// 자동 진입을 캔버스의 아무 클릭에 걸면 아바타 파츠를 고르다 갑자기 전체화면이 된다. 그래서
// 쓰는 자리는 `onWorldLoadStart` 하나다 — Unity 가 '월드 입장' 을 누른 직후에만 보낸다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { WorldHud } from '../../ui/WorldHud';
import { markFullscreenIntent } from '../../../../shared/ui/fullscreen';
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
  function toggle(container: HTMLElement) {
    return container.querySelector('.world-hud-fullscreen') as HTMLButtonElement;
  }

  it('우상단에 상시 있다 — 나갈 방법이 화면에 없으면 안 된다', () => {
    const { container } = render(<WorldHud />);
    expect(toggle(container)).not.toBeNull();
  });

  it('누르면 전체화면을 요청한다', () => {
    const { container } = render(<WorldHud />);
    fireEvent.click(toggle(container));
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('아이콘 상태는 브라우저를 따라간다 — FE state 가 아니다', () => {
    const { container } = render(<WorldHud />);
    expect(toggle(container).getAttribute('aria-pressed')).toBe('false');
    Object.defineProperty(document, 'fullscreenElement', { value: document.body, configurable: true });
    act(() => { document.dispatchEvent(new Event('fullscreenchange')); });
    expect(toggle(container).getAttribute('aria-pressed')).toBe('true');
  });
});

describe('자동 진입 시점 (-733)', () => {
  async function bootHost() {
    const { UnityHost } = await import('../../../../unity/host/UnityHost');
    render(<UnityHost />);
    await act(async () => {
      boots[0].resolve({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
    });
  }

  it('의도가 없으면 월드 입장에서도 부르지 않는다', async () => {
    await bootHost();
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    expect(request).not.toHaveBeenCalled();
  });

  it('의도가 있으면 월드 입장 신호에서 한 번 부른다', async () => {
    markFullscreenIntent();
    await bootHost();
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('두 번째 진입에서는 다시 부르지 않는다 — 의도는 한 번만 쓰인다', async () => {
    markFullscreenIntent();
    await bootHost();
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('브라우저가 거부해도 월드 진입을 막지 않는다', async () => {
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      value: () => Promise.reject(new Error('gesture required')),
      configurable: true,
    });
    markFullscreenIntent();
    await bootHost();
    expect(() => { act(() => { window.FestaUnity?.onWorldLoadStart?.(); }); }).not.toThrow();
  });
});
