// @vitest-environment jsdom
// 전체화면 유틸 (S15P21A604-733).
//
// 잠그는 것 셋 — ① 의도는 **한 번만** 쓰인다(다음 진입에 또 걸리면 안 된다) ② 브라우저가 거부해도
// 던지지 않는다(부르는 쪽이 월드 진입을 멈추면 안 된다) ③ 상태의 정본은 브라우저다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  consumeFullscreenIntent,
  enterFullscreen,
  exitFullscreen,
  isFullscreen,
  markFullscreenIntent,
  subscribeFullscreen,
} from '../../fullscreen';

beforeEach(() => {
  window.sessionStorage.clear();
  Object.defineProperty(document, 'fullscreenElement', { value: null, configurable: true, writable: true });
});

afterEach(() => {
  window.sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('전체화면 의도 (-733)', () => {
  it('표시하지 않으면 소비되지 않는다', () => {
    expect(consumeFullscreenIntent()).toBe(false);
  });

  it('표시하면 한 번만 소비된다 — 다음 월드 진입에 다시 걸리면 안 된다', () => {
    markFullscreenIntent();
    expect(consumeFullscreenIntent()).toBe(true);
    expect(consumeFullscreenIntent()).toBe(false);
  });

  it('표시 자체가 전체화면을 걸지 않는다 — 그 자리는 곧 OAuth 로 나간다', () => {
    const req = vi.fn();
    Object.defineProperty(document.documentElement, 'requestFullscreen', { value: req, configurable: true });
    markFullscreenIntent();
    expect(req).not.toHaveBeenCalled();
  });
});

describe('전체화면 진입·해제 (-733)', () => {
  it('브라우저가 거부해도 던지지 않고 false 를 돌려준다', async () => {
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      value: () => Promise.reject(new Error('gesture required')),
      configurable: true,
    });
    await expect(enterFullscreen()).resolves.toBe(false);
  });

  it('API 가 없는 환경에서도 던지지 않는다', async () => {
    Object.defineProperty(document.documentElement, 'requestFullscreen', { value: undefined, configurable: true });
    await expect(enterFullscreen()).resolves.toBe(false);
  });

  it('전체화면이 아니면 해제를 부르지 않는다 — 멱등이다', async () => {
    const exit = vi.fn(() => Promise.resolve());
    Object.defineProperty(document, 'exitFullscreen', { value: exit, configurable: true });
    await exitFullscreen();
    expect(exit).not.toHaveBeenCalled();
  });
});

describe('전체화면 상태의 정본 (-733)', () => {
  it('document.fullscreenElement 를 읽는다 — FE 가 따로 들고 있지 않는다', () => {
    expect(isFullscreen()).toBe(false);
    Object.defineProperty(document, 'fullscreenElement', { value: document.body, configurable: true });
    expect(isFullscreen()).toBe(true);
  });

  it('fullscreenchange 를 구독하고 해제하면 더 받지 않는다', () => {
    const seen = vi.fn();
    const stop = subscribeFullscreen(seen);
    document.dispatchEvent(new Event('fullscreenchange'));
    expect(seen).toHaveBeenCalledTimes(1);
    stop();
    document.dispatchEvent(new Event('fullscreenchange'));
    expect(seen).toHaveBeenCalledTimes(1);
  });
});
