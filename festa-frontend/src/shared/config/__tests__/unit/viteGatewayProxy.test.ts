// FE Host Gateway 의 dev 서버 계약 (S15P21A604-564).
//
// 여기서 잠그는 것은 성능이나 편의가 아니라 **깨졌을 때 조용한** 세 가지다.
//   ① /api 에 prefix rewrite 가 붙으면 요청은 200 인데 인증 쿠키만 빠진다 — BE 가 내리는
//      refresh_token(Path /api/v1/auth/refresh)·oauth_handoff(Path /api/v1/auth/oauth/complete)의
//      Path 가 브라우저 경로와 어긋나기 때문이다. Dev 배포의 /__dev/api 가 정확히 그 상태다.
//   ② changeOrigin 을 켜면 Host 가 업스트림으로 바뀌어 Spring 이 CORS 요청으로 보고, 허용 오리진
//      목록에 의존하게 된다. 끈 채로 두면 CORS 자체가 발생하지 않는다.
//   ③ polling 설정(컨테이너 dev 타깃)을 병합하다 proxy 를 통째로 잃으면 로컬만 조용히 예전 구조로
//      돌아간다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UserConfig } from 'vite';

async function loadServer(env: Record<string, string | undefined> = {}): Promise<UserConfig['server']> {
  vi.resetModules();
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  const module = await import('../../../../../vite.config');
  return (module.default as UserConfig).server;
}

afterEach(() => {
  delete process.env.VITE_USE_POLLING;
  delete process.env.VITE_PROXY_AI_TARGET;
  vi.resetModules();
});

describe('vite dev 게이트웨이 proxy', () => {
  it('/api 를 Spring 으로 넘긴다', async () => {
    const server = await loadServer();
    expect(server?.proxy?.['/api']).toMatchObject({ target: 'http://127.0.0.1:8080' });
  });

  it('/api 는 경로를 다시 쓰지 않는다 — 쿠키 Path 가 어긋나면 인증이 조용히 깨진다', async () => {
    const server = await loadServer();
    expect(server?.proxy?.['/api']).not.toHaveProperty('rewrite');
  });

  it('/api 는 changeOrigin 을 켜지 않는다 — Host 를 남겨 CORS 를 발생시키지 않는다', async () => {
    const server = await loadServer();
    expect((server?.proxy?.['/api'] as { changeOrigin?: boolean }).changeOrigin).toBeUndefined();
  });

  it('/unity 를 WebGL 정적 서버로 넘기고 prefix 만 벗긴다 — 정적 자산이라 안전하다', async () => {
    const server = await loadServer();
    const unity = server?.proxy?.['/unity'] as { target?: string; rewrite?: (p: string) => string };
    expect(unity.target).toBe('http://127.0.0.1:8000');
    expect(unity.rewrite?.('/unity/Build/x.wasm.unityweb')).toBe('/Build/x.wasm.unityweb');
  });

  it('/ai/v1 은 대상을 준 경우에만 등록한다 — 로컬 8000 은 WebGL 정적 서버가 쓰고 있다', async () => {
    expect((await loadServer())?.proxy?.['/ai/v1']).toBeUndefined();
    const withAi = await loadServer({ VITE_PROXY_AI_TARGET: 'http://127.0.0.1:8001' });
    expect(withAi?.proxy?.['/ai/v1']).toMatchObject({ target: 'http://127.0.0.1:8001' });
  });

  it('polling 을 켜도 proxy 가 살아 있다 — 컨테이너 dev 타깃에서 게이트웨이가 사라지지 않는다', async () => {
    const server = await loadServer({ VITE_USE_POLLING: 'true' });
    expect(server?.watch).toMatchObject({ usePolling: true });
    expect(server?.proxy?.['/api']).toBeDefined();
  });
});
