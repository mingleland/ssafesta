// resolver 실패 경로 (S15P21A604-341) — BUILD_BASE 는 모듈 로드 시 고정되므로 env 를 바꾼 뒤 재로드한다
import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe('resolveBuildDescriptor', () => {
  it('VITE_UNITY_BUILD_BASE 미설정이면 원인을 이름으로 말하는 오류를 던진다', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', '');
    const { resolveBuildDescriptor } = await import('../../resolver');
    await expect(resolveBuildDescriptor()).rejects.toThrow('VITE_UNITY_BUILD_BASE');
  });

  it('설정돼 있으면 `${BASE}/manifest.json` 을 읽고 키 4종을 돌려준다 (#60)', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', 'https://cdn.example.com/unity');
    const manifest = {
      loaderUrl: 'l.js',
      dataUrl: 'd.data',
      frameworkUrl: 'f.js',
      codeUrl: 'c.wasm',
    };
    const fetchMock = vi.fn(async (url: string) => {
      expect(url).toBe('https://cdn.example.com/unity/manifest.json');
      return { ok: true, json: async () => manifest } as Response;
    });
    vi.stubGlobal('fetch', fetchMock);
    const { resolveBuildDescriptor } = await import('../../resolver');
    await expect(resolveBuildDescriptor()).resolves.toEqual(manifest);
  });

  it('manifest 에 키가 빠지면 오류', async () => {
    vi.stubEnv('VITE_UNITY_BUILD_BASE', 'https://cdn.example.com/unity');
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ loaderUrl: 'l.js' }) }) as unknown as Response));
    const { resolveBuildDescriptor } = await import('../../resolver');
    await expect(resolveBuildDescriptor()).rejects.toThrow('빌드 URL');
  });
});
