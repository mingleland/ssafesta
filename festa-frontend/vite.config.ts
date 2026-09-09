import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'

// Runtime Asset Compiler 산출물을 **dev 서버에서만** 내보낸다 (S15P21A604-480).
// public/ 에 두면 프로덕션 이미지 빌드 컨텍스트에 섞일 여지가 생긴다. 여기 두면 dev 전용이
// 구조로 보장된다 — 벤더 라이선스가 REVIEW_REQUIRED 인 동안 필요한 경계다.
const RUNTIME_ASSET_ROUTE = '/assets/booth-runtime/'
const MIME: Record<string, string> = { '.glb': 'model/gltf-binary', '.json': 'application/json', '.webp': 'image/webp' }
function serveRuntimeAssets() {
  return {
    name: 'ssafesta-runtime-assets',
    apply: 'serve' as const,
    configureServer(server: { middlewares: { use: (fn: (req: { url?: string }, res: any, next: () => void) => void) => void } }) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? ''
        if (!url.startsWith(RUNTIME_ASSET_ROUTE)) return next()
        const name = normalize(url.slice(RUNTIME_ASSET_ROUTE.length).split('?')[0]).replace(/^(\.\.[/\\])+/, '')
        const file = join(process.cwd(), '.generated/runtime', name)
        if (!existsSync(file) || !statSync(file).isFile()) {
          res.statusCode = 404
          res.end('runtime asset 없음 — node tools/assets/compile-runtime-assets.mjs 를 돌린다')
          return
        }
        res.setHeader('Content-Type', MIME[extname(file)] ?? 'application/octet-stream')
        createReadStream(file).pipe(res)
      })
    },
  }
}

// FE Host Gateway (S15P21A604-564) — dev 서버가 하나의 오리진 뒤에서 API·WebGL 을 함께 내보낸다.
// 목적은 React 와 Unity 가 환경별 Backend 주소를 직접 알지 않게 하는 것이다. Dev 배포는 이미 같은
// 모양이고(nginx `/__dev/{front,api,ai}`), Demo 는 `/unity/` 를 same-origin 으로 낸다 — Local 만
// 게이트웨이가 없어 Unity 가 빌드 타임 Prod 로 새어 나갔다.
//
// `/api` 는 **경로를 다시 쓰지 않는다.** 브라우저가 보는 경로가 `/api/v1/...` 그대로여야 BE 가 내리는
// 인증 쿠키의 Path(`/api/v1/auth/refresh`·`/api/v1/auth/oauth/complete`)가 매치된다. 프리픽스를
// 붙이면 요청은 성공하는데 쿠키만 조용히 빠진다.
//
// `changeOrigin` 은 켜지 않는다. Host 가 `localhost:5173` 로 남으면 Spring 이 Origin==Host 로 보고
// CORS 검사 자체를 타지 않는다 — 허용 오리진 목록을 늘리지 않고 문제를 없앤다.
//
// `/unity` 는 정적 자산이라 rewrite 해도 안전하다(Demo nginx 의 `alias` 와 같은 효과). 응답 헤더는
// 건드리지 않는다 — Brotli 빌드의 `Content-Encoding: br` 과 `application/wasm` 이 유실되면 T-19
// (`WebAssembly streaming compilation failed`)가 재발한다.
//
// `/ai/v1` 은 기본으로 열지 않는다. 로컬 8000 은 WebGL 정적 서버가 이미 쓰고 있어 FastAPI 대상이
// 정해져야 켤 수 있다 — `VITE_PROXY_AI_TARGET` 을 준 경우에만 등록한다.
const gatewayProxy = {
  '/api': { target: process.env.VITE_PROXY_API_TARGET ?? 'http://127.0.0.1:8080' },
  '/unity': {
    target: process.env.VITE_PROXY_UNITY_TARGET ?? 'http://127.0.0.1:8000',
    rewrite: (path: string) => path.replace(/^\/unity/, ''),
  },
  ...(process.env.VITE_PROXY_AI_TARGET
    ? { '/ai/v1': { target: process.env.VITE_PROXY_AI_TARGET } }
    : {}),
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), serveRuntimeAssets()],
  server: {
    proxy: gatewayProxy,
    // polling 은 컨테이너 dev 타깃에서만 켠다 — Windows bind mount 는 inotify 이벤트를 컨테이너로
    // 전달하지 않아 HMR 이 조용히 죽는다(느린 게 아니라 아예 안 온다). CPU 를 계속 쓰므로 호스트
    // 실행(기본 경로)에서는 끈 채로 둔다. Dockerfile 의 dev 타깃이 이 값을 세운다.
    ...(process.env.VITE_USE_POLLING === 'true'
      ? { watch: { usePolling: true, interval: 300 } }
      : {}),
  },
  test: {
    // 기본은 node — 순수 함수 테스트가 대부분이고 jsdom을 전역으로 켜면 그 전부가 느려진다.
    // 컴포넌트 테스트(.tsx)는 파일 상단 `// @vitest-environment jsdom` docblock으로 개별 전환한다 (G-4).
    environment: 'node',
    // tools/ 는 빌드타임 스크립트다(.mjs). 앱 코드가 아니라 tsc 대상이 아니지만, 판정 로직이
    // 들어 있어 회귀가 필요하다 — 그래서 테스트만 여기에 포함한다 (S15P21A604-480).
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'tools/**/*.test.mjs'],
  },
})
