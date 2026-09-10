import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'
// 게이트웨이 라우팅은 tools/devGateway.mjs 가 소유한다 — 이 파일을 import 하면 plugin-react 까지
// 함께 로드돼 테스트 스위트가 무거워진다(LJH T-84). 계약 테스트는 tools/devGateway.test.mjs 다.
import { createDevServerConfig } from './tools/devGateway.mjs'

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

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), serveRuntimeAssets()],
  // FE Host Gateway (S15P21A604-564) — dev 서버가 하나의 오리진 뒤에서 API·WebGL 을 함께 내보낸다.
  // React 와 Unity 가 환경별 Backend 주소를 직접 알지 않게 하는 것이 목적이다. 라우팅 규칙과 그
  // 근거는 tools/devGateway.mjs 에 있다.
  server: createDevServerConfig(process.env),
  test: {
    // 기본은 node — 순수 함수 테스트가 대부분이고 jsdom을 전역으로 켜면 그 전부가 느려진다.
    // 컴포넌트 테스트(.tsx)는 파일 상단 `// @vitest-environment jsdom` docblock으로 개별 전환한다 (G-4).
    environment: 'node',
    // tools/ 는 빌드타임 스크립트다(.mjs). 앱 코드가 아니라 tsc 대상이 아니지만, 판정 로직이
    // 들어 있어 회귀가 필요하다 — 그래서 테스트만 여기에 포함한다 (S15P21A604-480).
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'tools/**/*.test.mjs'],
  },
})
