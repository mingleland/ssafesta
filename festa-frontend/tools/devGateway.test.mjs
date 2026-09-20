// FE Host Gateway 의 dev 서버 계약 (S15P21A604-564).
//
// 여기서 잠그는 것은 편의가 아니라 **깨졌을 때 조용한** 세 가지다.
//   ① /api 에 prefix rewrite 가 붙으면 요청은 200 인데 인증 쿠키만 빠진다 — BE 가 내리는
//      refresh_token(Path /api/v1/auth/refresh)·oauth_handoff(Path /api/v1/auth/oauth/complete)의
//      Path 가 브라우저 경로와 어긋나기 때문이다. Dev 배포의 /__dev/api 가 정확히 그 상태다.
//   ② changeOrigin 을 켜면 Host 가 업스트림으로 바뀌어 Spring 이 CORS 요청으로 보고, 허용 오리진
//      목록에 의존하게 된다. 끈 채로 두면 CORS 자체가 발생하지 않는다.
//   ③ polling 설정(컨테이너 dev 타깃)을 병합하다 proxy 를 통째로 잃으면 로컬만 조용히 예전
//      구조로 돌아간다.
import { describe, expect, it } from 'vitest';
import { createDevServerConfig, createGatewayProxy } from './devGateway.mjs';

describe('dev 게이트웨이 proxy', () => {
  it('/api 를 Spring 으로 넘긴다', () => {
    expect(createGatewayProxy({})['/api']).toEqual({ target: 'http://127.0.0.1:8080' });
  });

  it('/api 는 경로를 다시 쓰지 않는다 — 쿠키 Path 가 어긋나면 인증이 조용히 깨진다', () => {
    expect(createGatewayProxy({})['/api']).not.toHaveProperty('rewrite');
  });

  it('/api 는 changeOrigin 을 켜지 않는다 — Host 를 남겨 CORS 를 발생시키지 않는다', () => {
    expect(createGatewayProxy({})['/api'].changeOrigin).toBeUndefined();
  });

  it('/unity 를 WebGL 정적 서버로 넘기고 prefix 만 벗긴다 — 정적 자산이라 안전하다', () => {
    const unity = createGatewayProxy({})['/unity'];
    expect(unity.target).toBe('http://127.0.0.1:8000');
    expect(unity.rewrite('/unity/Build/x.wasm.unityweb')).toBe('/Build/x.wasm.unityweb');
  });

  // local 개발용 auth gateway 보정 (S15P21A604-649, GitLab #177). 여기서 잠그는 것은 셋이다 —
  // ① /oauth2 가 /api 와 **같은** 대상으로 간다(다른 BE 로 가면 JSESSIONID 가 갈려 state 검증이 죽는다)
  // ② rewrite·changeOrigin 없음 — Spring 이 Host 로 own-origin 을 판정하므로 /api 와 같은 조건이어야 한다
  // ③ provider 콜백 /login/oauth2/code/* 는 프록시하지 않는다 — 등록된 BE 주소로 직접 돌아온다
  it('/oauth2 를 /api 와 같은 Spring 으로 넘긴다 — OAuth 시작의 상대 Location 이 FE 오리진에서 죽지 않게', () => {
    const proxy = createGatewayProxy({});
    expect(proxy['/oauth2']).toEqual({ target: 'http://127.0.0.1:8080' });
    expect(proxy['/oauth2'].target).toBe(proxy['/api'].target);
    expect(proxy['/oauth2']).not.toHaveProperty('rewrite');
    expect(proxy['/oauth2'].changeOrigin).toBeUndefined();
  });

  it('/oauth2 는 API 대상 재지정을 그대로 따른다 — 둘이 갈리면 세션이 갈린다', () => {
    const proxy = createGatewayProxy({ VITE_PROXY_API_TARGET: 'http://spring:8081' });
    expect(proxy['/oauth2'].target).toBe('http://spring:8081');
  });

  it('provider 콜백(/login/oauth2/code)은 프록시하지 않는다 — 등록된 redirect URI 는 BE 주소다', () => {
    const keys = Object.keys(createGatewayProxy({}));
    expect(keys.some((k) => k.startsWith('/login'))).toBe(false);
  });

  // STOMP 실시간 소켓 (S15P21A604-840). 빠지면 vite 가 /ws 를 자기 HMR 소켓으로 받아
  // code=1006 재연결 실패만 남는다.
  it('/ws 를 /api 와 같은 Spring 으로 넘긴다 — ws-token 발급처와 소켓 수신처가 갈리면 안 된다', () => {
    const proxy = createGatewayProxy({});
    expect(proxy['/ws'].target).toBe(proxy['/api'].target);
    expect(proxy['/ws']).not.toHaveProperty('rewrite');
  });

  it('/ws 는 ws: true 다 — 없으면 Upgrade 가 넘어가지 않아 소켓만 조용히 죽는다', () => {
    expect(createGatewayProxy({})['/ws'].ws).toBe(true);
  });

  it('/ws 도 API 대상 재지정을 따른다', () => {
    expect(createGatewayProxy({ VITE_PROXY_API_TARGET: 'http://spring:8081' })['/ws'].target)
      .toBe('http://spring:8081');
  });

  it('/ai/v1 은 대상을 준 경우에만 등록한다 — 로컬 8000 은 WebGL 정적 서버가 쓰고 있다', () => {
    expect(createGatewayProxy({})['/ai/v1']).toBeUndefined();
    expect(createGatewayProxy({ VITE_PROXY_AI_TARGET: 'http://127.0.0.1:8001' })['/ai/v1'])
      .toEqual({ target: 'http://127.0.0.1:8001' });
  });

  it('프록시 대상은 환경변수로 재지정할 수 있다', () => {
    const proxy = createGatewayProxy({
      VITE_PROXY_API_TARGET: 'http://spring:8080',
      VITE_PROXY_UNITY_TARGET: 'http://webgl:80',
    });
    expect(proxy['/api'].target).toBe('http://spring:8080');
    expect(proxy['/unity'].target).toBe('http://webgl:80');
  });
});

describe('dev 서버 설정 병합', () => {
  it('기본은 polling 없이 proxy 만 둔다', () => {
    const server = createDevServerConfig({});
    expect(server.proxy['/api']).toBeDefined();
    expect(server.watch).toBeUndefined();
  });

  it('polling 을 켜도 proxy 가 살아 있다 — 컨테이너 dev 타깃에서 게이트웨이가 사라지지 않는다', () => {
    const server = createDevServerConfig({ VITE_USE_POLLING: 'true' });
    expect(server.watch).toEqual({ usePolling: true, interval: 300 });
    expect(server.proxy['/api']).toBeDefined();
  });
});
