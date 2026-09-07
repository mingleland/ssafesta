// @vitest-environment jsdom
// loader — createUnityInstance 에 넘기는 렌더 해상도 상한 (S15P21A604-484)
//
// 이 값을 정할 수 있는 곳은 여기뿐이다. Unity 안에서 Screen.SetResolution 으로 낮추면 로더의
// matchWebGLToCanvasSize 가 되돌리고, canvas.width/height 를 JS 로 바꾸면 렌더 루프가 멈춘다 —
// 게임 파트가 둘 다 실측으로 확인하고 되돌린 경로다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadUnityBuild, resolveDevicePixelRatio } from '../../loader';

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
  it('상한을 넘는 화면은 1.5 로 낮춘다 — 실측 DPR 2.2 는 표시 크기의 4.84배를 그린다', () => {
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
