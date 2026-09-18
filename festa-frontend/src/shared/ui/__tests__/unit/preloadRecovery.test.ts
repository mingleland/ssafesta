// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { handlePreloadError } from '../../preloadRecovery';

function fire(reload: () => void) {
  const event = new CustomEvent('vite:preloadError', { cancelable: true, detail: new Error('failed to fetch chunk') });
  const reloaded = handlePreloadError(event, reload);
  return { event, reloaded };
}

describe('preload 실패 복구', () => {
  afterEach(() => {
    sessionStorage.clear();
    document.head.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('옛 chunk 실패 첫 회 — 기본 동작을 막고 한 번 새로고침한다', () => {
    const reload = vi.fn();
    const { event, reloaded } = fire(reload);
    expect(event.defaultPrevented).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(reloaded).toBe(true);
  });

  it('같은 번들에서 다시 실패 — 새로고침하지 않고 오류를 남긴다 (무한 루프 금지)', () => {
    const reload = vi.fn();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    fire(reload);
    const second = fire(reload);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(second.reloaded).toBe(false);
    expect(error).toHaveBeenCalledTimes(1);
  });

  it('새 번들이 뜨면 키가 달라져 다시 한 번 새로고침할 수 있다', () => {
    const reload = vi.fn();
    const script = document.createElement('script');
    script.type = 'module';
    script.src = '/assets/index-old.js';
    document.head.append(script);
    fire(reload);
    script.src = '/assets/index-new.js';
    fire(reload);
    expect(reload).toHaveBeenCalledTimes(2);
  });
});

