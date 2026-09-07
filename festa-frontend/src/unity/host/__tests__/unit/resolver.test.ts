// resolver — base 는 호출 시점에 런타임 주입값 → 빌드 타임 순으로 읽고, manifest 상대경로를 base 기준으로 푼다
// (S15P21A604-341 실패 경로 · -427 런타임 base·상대경로 해석, #127·#128 §4)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveBuildDescriptor } from '../../resolver';

const MANIFEST = { loaderUrl: 'Build/a.loader.js', dataUrl: 'Build/a.data', frameworkUrl: 'Build/a.framework.js', codeUrl: 'Build/a.wasm' };

function stubFetch(manifest: unknown = MANIFEST, ok = true) {
  const fetchMock = vi.fn(async () => ({ ok, status: ok ? 200 : 404, json: async () => manifest }) as unknown as Response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

beforeEach(() => {
  vi.stubGlobal('window', { location: { origin: 'https://demo.example.com' }, __FESTA_CONFIG__: {} });
  vi.stubEnv('VITE_UNITY_BUILD_BASE', '');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('resolveBuildDescriptor', () => {
  it('런타임·빌드 값이 둘 다 없으면 두 이름을 말하는 오류를 던진다', async () => {
    await expect(resolveBuildDescriptor()).rejects.toThrow(/PUBLIC_UNITY_BUILD_BASE.*VITE_UNITY_BUILD_BASE/);
  });

  it('빌드 타임 값(fallback)으로 `${BASE}/manifest.json` 을 읽고 상대경로 4종을 base 기준 절대 URL 로 돌려준다 (#60)', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', 'https://cdn.example.com/unity');
    const fetchMock = stubFetch();
    await expect(resolveBuildDescriptor()).resolves.toEqual({
      loaderUrl: 'https://cdn.example.com/unity/Build/a.loader.js',
      dataUrl: 'https://cdn.example.com/unity/Build/a.data',
      frameworkUrl: 'https://cdn.example.com/unity/Build/a.framework.js',
      codeUrl: 'https://cdn.example.com/unity/Build/a.wasm',
    });
    expect(fetchMock).toHaveBeenCalledWith('https://cdn.example.com/unity/manifest.json');
  });

  it('런타임 주입값이 있으면 빌드 타임 값보다 우선한다 — 같은 이미지로 환경마다 base 만 바뀐다 (#127)', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', 'https://build-time.example.com/unity');
    vi.stubGlobal('window', { location: { origin: 'https://demo.example.com' }, __FESTA_CONFIG__: { unityBuildBase: '/unity/' } });
    const fetchMock = stubFetch();
    const d = await resolveBuildDescriptor();
    expect(fetchMock).toHaveBeenCalledWith('https://demo.example.com/unity/manifest.json');
    expect(d.loaderUrl).toBe('https://demo.example.com/unity/Build/a.loader.js');
  });

  it('manifest 가 절대 URL 을 주면 그대로 통과한다', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', '/unity/');
    stubFetch({ ...MANIFEST, codeUrl: 'https://cdn.example.com/x.wasm' });
    const d = await resolveBuildDescriptor();
    expect(d.codeUrl).toBe('https://cdn.example.com/x.wasm');
    expect(d.loaderUrl).toBe('https://demo.example.com/unity/Build/a.loader.js');
  });

  it('잘못된 base 는 fetch 전에 이름을 말하며 실패한다', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', 'http://');
    const fetchMock = stubFetch();
    await expect(resolveBuildDescriptor()).rejects.toThrow('해석할 수 없다');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('manifest 응답이 실패하면 상태 코드를 말한다', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', '/unity/');
    stubFetch(MANIFEST, false);
    await expect(resolveBuildDescriptor()).rejects.toThrow('404');
  });

  it('manifest 에 키가 빠지면 오류', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', '/unity/');
    stubFetch({ loaderUrl: 'l.js' });
    await expect(resolveBuildDescriptor()).rejects.toThrow('빌드 URL');
  });
});
