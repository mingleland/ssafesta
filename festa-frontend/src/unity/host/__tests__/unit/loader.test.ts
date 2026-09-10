// @vitest-environment jsdom
// loader — createUnityInstance 에 넘기는 렌더 해상도 상한 (S15P21A604-484)
//
// 이 값을 정할 수 있는 곳은 여기뿐이다. Unity 안에서 Screen.SetResolution 으로 낮추면 로더의
// matchWebGLToCanvasSize 가 되돌리고, canvas.width/height 를 JS 로 바꾸면 렌더 루프가 멈춘다 —
// 게임 파트가 둘 다 실측으로 확인하고 되돌린 경로다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadUnityBuild, resolveDevicePixelRatio, watchDevicePixelRatio } from '../../loader';
import type { UnityInstance } from '../../types';

// jsdom 에서 window 를 통째로 stub 하면 document 까지 사라진다 — devicePixelRatio 만 바꾼다.
function stubRatio(value: unknown) {
  Object.defineProperty(window, 'devicePixelRatio', { value, configurable: true, writable: true });
}

const DESCRIPTOR = {
  loaderUrl: 'https://cdn.example.com/Build/a.loader.js',
  dataUrl: 'https://cdn.example.com/Build/a.data',
  frameworkUrl: 'https://cdn.example.com/Build/a.framework.js',
  codeUrl: 'https://cdn.example.com/Build/a.wasm',
};

vi.mock('../../resolver', () => ({ resolveBuildDescriptor: async () => DESCRIPTOR }));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('resolveDevicePixelRatio', () => {
  it('상한을 넘는 화면은 1.5 로 낮춘다 — 상한이 없으면 표시 크기의 3배 넘게 그린다', () => {
    stubRatio(2.2);
    expect(resolveDevicePixelRatio()).toBe(1.5);
  });

  it('상한 이하는 그대로 둔다 — 저밀도 화면을 더 흐리게 만들 이유가 없다', () => {
    stubRatio(1);
    expect(resolveDevicePixelRatio()).toBe(1);
  });

  it('값을 읽을 수 없으면 1 이다', () => {
    stubRatio(undefined);
    expect(resolveDevicePixelRatio()).toBe(1);
    stubRatio(Number.NaN);
    expect(resolveDevicePixelRatio()).toBe(1);
  });

  it('0 이하는 1 이다 — 백버퍼가 0 픽셀이 되면 안 된다', () => {
    stubRatio(0);
    expect(resolveDevicePixelRatio()).toBe(1);
    stubRatio(-1);
    expect(resolveDevicePixelRatio()).toBe(1);
  });
});

describe('watchDevicePixelRatio', () => {
  // 부팅 때 한 번 정한 값이 세션 내내 남는 것이 #143 의 남은 결함이다. Unity 는 이 값을 1초 주기로
  // 다시 읽으므로(실측), 밀도가 바뀔 때 갱신하면 재부팅 없이 따라간다.
  function stubMatchMedia() {
    const listeners: Array<() => void> = [];
    const queries: string[] = [];
    const matchMedia = vi.fn((query: string) => {
      queries.push(query);
      return {
        addEventListener: (_: string, fn: () => void) => listeners.push(fn),
        removeEventListener: (_: string, fn: () => void) => {
          const at = listeners.indexOf(fn);
          if (at >= 0) listeners.splice(at, 1);
        },
      } as unknown as MediaQueryList;
    });
    vi.stubGlobal('matchMedia', matchMedia);
    return { listeners, queries };
  }

  it('밀도가 바뀌면 상한을 다시 적용해 Module 에 쓴다', () => {
    stubRatio(1.5);
    const { listeners } = stubMatchMedia();
    const instance = { Module: { devicePixelRatio: 1.5 } } as unknown as UnityInstance;

    watchDevicePixelRatio(instance);
    stubRatio(3);
    listeners[0]();

    expect(instance.Module?.devicePixelRatio).toBe(1.5); // 상한이 그대로 걸린다
    stubRatio(1);
    listeners[0]();
    expect(instance.Module?.devicePixelRatio).toBe(1); // 낮아진 화면은 낮아진 값으로
  });

  it('해제하면 더 이상 쓰지 않는다 — 인스턴스가 죽은 뒤 남지 않게', () => {
    stubRatio(1);
    const { listeners } = stubMatchMedia();
    const instance = { Module: { devicePixelRatio: 1 } } as unknown as UnityInstance;

    const stop = watchDevicePixelRatio(instance);
    const fire = listeners[0];
    stop();
    stubRatio(3);
    fire();

    expect(instance.Module?.devicePixelRatio).toBe(1);
  });

  it('Module 이 없는 인스턴스(mock 로더)에서도 터지지 않는다', () => {
    stubRatio(2);
    const { listeners } = stubMatchMedia();
    const instance = {} as unknown as UnityInstance;

    watchDevicePixelRatio(instance);
    expect(() => listeners[0]()).not.toThrow();
  });
});

describe('loadUnityBuild', () => {
  beforeEach(() => {
    const script = { src: '', onload: null as null | (() => void), onerror: null };
    vi.spyOn(document, 'createElement').mockReturnValue(script as unknown as HTMLScriptElement);
    vi.spyOn(document.body, 'appendChild').mockImplementation(((node: unknown) => {
      queueMicrotask(() => (node as typeof script).onload?.());
      return node;
    }) as typeof document.body.appendChild);
  });

  it('config 에 상한이 걸린 devicePixelRatio 를 실어 보낸다', async () => {
    const createUnityInstance = vi.fn(async () => ({}) as never);
    stubRatio(2.2);
    vi.stubGlobal('createUnityInstance', createUnityInstance);

    const canvas = {} as HTMLCanvasElement;
    await loadUnityBuild(canvas, () => {});

    expect(createUnityInstance).toHaveBeenCalledTimes(1);
    const [passedCanvas, config] = createUnityInstance.mock.calls[0] as unknown as [
      HTMLCanvasElement,
      Record<string, unknown>,
    ];
    expect(passedCanvas).toBe(canvas);
    expect(config.devicePixelRatio).toBe(1.5);
    expect(config.dataUrl).toBe(DESCRIPTOR.dataUrl);
  });
});
