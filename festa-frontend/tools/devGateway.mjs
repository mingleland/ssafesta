// FE Host Gateway 의 dev 서버 라우팅 (S15P21A604-564).
//
// vite.config.ts 가 아니라 여기 있는 이유는 **테스트 때문**이다. 이 계약을 검증하려면 설정 객체를
// import 해야 하는데, vite.config.ts 를 import 하면 @vitejs/plugin-react 까지 함께 로드된다.
// 그 무게가 전체 스위트로 번져 시간에 민감한 다른 테스트를 넘어뜨렸다(!537 · LJH T-84).
// 순수 함수로 떼어 두면 계약은 그대로 잠그면서 그 비용이 사라진다.
//
// `.mjs` 인 것도 같은 이유다 — tools/ 는 이미 빌드타임 스크립트 자리이고 vitest include 에
// `tools/**/*.test.mjs` 가 들어 있다(S15P21A604-480).

/**
 * dev 서버 proxy 설정을 만든다.
 *
 * `/api` 는 **경로를 다시 쓰지 않는다.** 브라우저가 보는 경로가 `/api/v1/...` 그대로여야 BE 가
 * 내리는 인증 쿠키의 Path(`/api/v1/auth/refresh`·`/api/v1/auth/oauth/complete`)가 매치된다.
 * 프리픽스를 붙이면 요청은 200 인데 쿠키만 조용히 빠진다 — Dev 배포의 `/__dev/api` 가 그 상태다.
 *
 * `changeOrigin` 은 켜지 않는다. Host 가 FE 오리진으로 남으면 Spring 이 Origin==Host 로 보고
 * CORS 검사 자체를 타지 않는다 — 허용 오리진 목록을 늘리지 않고 문제를 없애는 쪽이다.
 *
 * `/unity` 는 정적 자산이라 rewrite 해도 안전하다(Demo nginx 의 `alias` 와 같은 효과). 응답 헤더는
 * 건드리지 않는다 — Brotli 빌드의 `Content-Encoding: br` 과 `application/wasm` 이 유실되면 T-19
 * (`WebAssembly streaming compilation failed`)가 재발한다.
 *
 * `/ai/v1` 은 기본으로 열지 않는다. 로컬 8000 은 WebGL 정적 서버가 이미 쓰고 있어 FastAPI 대상이
 * 정해져야 켤 수 있다.
 *
 * `/oauth2` 는 **local 개발용 auth gateway 보정**이다(S15P21A604-649, GitLab #177). OAuth 시작
 * `GET /api/v1/auth/oauth/{provider}` 가 Spring 의 `302 Location: /oauth2/authorization/{provider}`
 * (상대 경로)로 이어지는데, 이 prefix 가 게이트웨이에 없으면 브라우저가 FE 오리진의 `/oauth2/...` 로
 * 가서 index.html 을 받는다. 같은 target 으로 넘기면 BE 가 `Set-Cookie: JSESSIONID`(host-only,
 * 포트 무관)를 심고 provider 로 보낸다. provider 콜백(`/login/oauth2/code/*`)은 등록된 BE 주소로
 * 직접 돌아오므로 **여기서 받지 않는다** — 그것을 프록시하면 provider 콘솔 등록값을 포트마다
 * 바꿔야 한다. **dev/demo/prod nginx 계약 변경이 아니다** — dev 의 `dev.conf` 에는 `/oauth2/` route
 * 가 없고 OAuth 시작이 `api.<domain>` vhost 를 탄다. 로컬만 한 오리진으로 접기 때문에 필요한 보정이다.
 *
 * @param {Record<string, string | undefined>} env process.env
 */
export function createGatewayProxy(env = {}) {
  const api = env.VITE_PROXY_API_TARGET ?? 'http://127.0.0.1:8080';
  return {
    '/api': { target: api },
    '/oauth2': { target: api },
    '/unity': {
      target: env.VITE_PROXY_UNITY_TARGET ?? 'http://127.0.0.1:8000',
      rewrite: (path) => path.replace(/^\/unity/, ''),
    },
    ...(env.VITE_PROXY_AI_TARGET ? { '/ai/v1': { target: env.VITE_PROXY_AI_TARGET } } : {}),
  };
}

/**
 * dev 서버 설정 전체. polling 은 컨테이너 dev 타깃에서만 켠다 — Windows bind mount 는 inotify
 * 이벤트를 컨테이너로 전달하지 않아 HMR 이 조용히 죽는다(느린 게 아니라 아예 안 온다). CPU 를
 * 계속 쓰므로 호스트 실행(기본 경로)에서는 끈 채로 둔다. Dockerfile 의 dev 타깃이 이 값을 세운다.
 *
 * @param {Record<string, string | undefined>} env process.env
 */
export function createDevServerConfig(env = {}) {
  return {
    proxy: createGatewayProxy(env),
    ...(env.VITE_USE_POLLING === 'true' ? { watch: { usePolling: true, interval: 300 } } : {}),
  };
}
