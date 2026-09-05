// Unity 자산 warm-up (S15P21A604-430) — 요지는 "받는다" 가 아니라 "실패해도 진입을 막지 않는다" 와
// "단계에 맞는 것만 받는다" 다. 인스턴스를 만들지 않는 것도 여기서 고정한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const MANIFEST = {
  loaderUrl: 'Build/a.loader.js',
  frameworkUrl: 'Build/a.framework.js',
  codeUrl: 'Build/a.wasm',
  dataUrl: 'Build/a.data',
};

function stubFetch(manifestOk = true) {
  const calls: string[] = [];
  const fetchMock = vi.fn(async (url: string) => {
    calls.push(url);
    if (url.endsWith('manifest.json')) {
      return { ok: manifestOk, json: async () => MANIFEST } as unknown as Response;
    }
    return { ok: true, arrayBuffer: async () => new ArrayBuffer(8) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

const flush = async () => { for (let i = 0; i < 40; i += 1) await Promise.resolve(); };

beforeEach(() => {
  vi.stubGlobal('window', { location: { origin: 'https://demo.example.com' }, __FESTA_CONFIG__: {} });
  vi.stubGlobal('navigator', {});
  vi.stubEnv('VITE_UNITY_BUILD_BASE', 'https://cdn.example.com/unity');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe('warmUpUnityAssets', () => {
  it('landing 단계는 manifest 와 loader 까지만 받는다 — 첫 페인트와 큰 파일을 경쟁시키지 않는다', async () => {
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('landing');
    await flush();
    expect(calls).toEqual([
      'https://cdn.example.com/unity/manifest.json',
      'https://cdn.example.com/unity/Build/a.loader.js',
    ]);
  });

  it('authenticated 단계는 wasm·data 까지 넓힌다', async () => {
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('authenticated');
    await flush();
    expect(calls.some((u) => u.endsWith('a.wasm'))).toBe(true);
    expect(calls.some((u) => u.endsWith('a.data'))).toBe(true);
  });

  it('데이터 절약이 켜져 있으면 큰 파일은 건너뛴다 — 작은 것은 그대로 받는다', async () => {
    vi.stubGlobal('navigator', { connection: { saveData: true } });
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('authenticated');
    await flush();
    expect(calls.some((u) => u.endsWith('a.loader.js'))).toBe(true);
    expect(calls.some((u) => u.endsWith('a.wasm'))).toBe(false);
    expect(calls.some((u) => u.endsWith('a.data'))).toBe(false);
  });

  it('2g 회선도 큰 파일을 건너뛴다', async () => {
    vi.stubGlobal('navigator', { connection: { effectiveType: '2g' } });
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('authenticated');
    await flush();
    expect(calls.some((u) => u.endsWith('a.data'))).toBe(false);
  });

  it('base 미설정이면 아무 요청도 하지 않는다', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', '');
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('landing');
    await flush();
    expect(calls).toEqual([]);
  });

  it('manifest 가 실패해도 예외를 밖으로 던지지 않는다 — 진입은 loader 가 다시 처리한다', async () => {
    stubFetch(false);
    const { warmUpUnityAssets } = await import('../../warmup');
    expect(() => warmUpUnityAssets('authenticated')).not.toThrow();
    await flush();
  });

  it('네트워크가 통째로 죽어도 조용히 지나간다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));
    const { warmUpUnityAssets } = await import('../../warmup');
    expect(() => warmUpUnityAssets('landing')).not.toThrow();
    await flush();
  });

  it('중단 함수를 부르면 남은 요청을 취소한다', async () => {
    const calls = stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    const abort = warmUpUnityAssets('authenticated');
    abort();
    await flush();
    expect(calls.length).toBeLessThan(5);
  });

  it('Unity 인스턴스를 만들지 않는다 — createUnityInstance 를 부르지 않는다', async () => {
    const createUnityInstance = vi.fn();
    vi.stubGlobal('window', { location: { origin: 'https://demo.example.com' }, __FESTA_CONFIG__: {}, createUnityInstance });
    stubFetch();
    const { warmUpUnityAssets } = await import('../../warmup');
    warmUpUnityAssets('authenticated');
    await flush();
    expect(createUnityInstance).not.toHaveBeenCalled();
  });
});
