// @vitest-environment jsdom
// FE Host Gateway (S15P21A604-564) — Unity 가 읽을 apiBaseUrl 이 **로더 스크립트보다 먼저** 서 있는가.
//
// 순서가 계약인 이유: Unity 의 HostRuntimeConfig 는 window.__FESTA_CONFIG__.apiBaseUrl 을 한 번만
// 읽고 캐시한다. 스크립트가 먼저 들어가면 값이 비어 있는 채로 읽히고, ApiServices 는 빌드 타임
// Prod(https://api.ssafesta.world)로 내려간다 — 로컬에서 월드가 말없이 안 뜨던 그 경로다.
// 그래서 "채워지긴 한다" 가 아니라 "스크립트 주입 시점에 이미 있다" 를 단정한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../resolver', () => ({
  resolveBuildDescriptor: async () => ({
    loaderUrl: 'http://localhost:3000/unity/Build/x.loader.js',
    dataUrl: 'http://localhost:3000/unity/Build/x.data.unityweb',
    frameworkUrl: 'http://localhost:3000/unity/Build/x.framework.js.unityweb',
    codeUrl: 'http://localhost:3000/unity/Build/x.wasm.unityweb',
  }),
}));

/** 스크립트가 DOM 에 들어간 순간 읽힌 apiBaseUrl. 없으면 'MISSING'. */
let apiBaseUrlAtInjection: string | null = null;

beforeEach(() => {
  apiBaseUrlAtInjection = null;
  delete window.__FESTA_CONFIG__;
  // 개발자마다 다른 .env.local 이 판정에 새어 들지 않게 비운다.
  vi.stubEnv('VITE_API_BASE_URL', '');
  vi.spyOn(document.body, 'appendChild').mockImplementation(((node: Node) => {
    apiBaseUrlAtInjection = window.__FESTA_CONFIG__?.apiBaseUrl ?? 'MISSING';
    // 실제 로더 스크립트가 없으므로 로드 성공을 대신 알린다.
    queueMicrotask(() => (node as HTMLScriptElement).onload?.(new Event('load')));
    return node;
  }) as typeof document.body.appendChild);
  window.createUnityInstance = vi.fn().mockResolvedValue({
    SendMessage: vi.fn(),
    SetFullscreen: vi.fn(),
    Quit: async () => {},
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  delete window.createUnityInstance;
  delete window.__FESTA_CONFIG__;
});

describe('loadUnityBuild — runtime config 공급 순서', () => {
  it('로더 스크립트를 넣기 전에 apiBaseUrl 이 이미 서 있다', async () => {
    const { loadUnityBuild } = await import('../../loader');

    await loadUnityBuild(document.createElement('canvas'), () => {});

    expect(apiBaseUrlAtInjection).toBe(window.location.origin);
  });

  it('설정이 하나도 없어도 빈 값을 넘기지 않는다 — Prod 무언 fallback 금지', async () => {
    const { loadUnityBuild } = await import('../../loader');

    await loadUnityBuild(document.createElement('canvas'), () => {});

    expect(apiBaseUrlAtInjection).not.toBe('MISSING');
    expect(apiBaseUrlAtInjection).not.toBe('');
  });

  it('이미 주입된 값은 덮어쓰지 않는다 — 배포 entrypoint 가 정본이다', async () => {
    window.__FESTA_CONFIG__ = { apiBaseUrl: 'https://api.example.test' };
    const { loadUnityBuild } = await import('../../loader');

    await loadUnityBuild(document.createElement('canvas'), () => {});

    expect(apiBaseUrlAtInjection).toBe('https://api.example.test');
  });
});
