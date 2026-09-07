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

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), serveRuntimeAssets()],
  // 컨테이너 dev 타깃에서만 켠다 — Windows bind mount 는 inotify 이벤트를 컨테이너로 전달하지
  // 않아 HMR 이 조용히 죽는다(느린 게 아니라 아예 안 온다). polling 은 CPU 를 계속 쓰므로
  // 호스트 실행(기본 경로)에서는 끈 채로 둔다. Dockerfile 의 dev 타깃이 이 값을 세운다.
  server: process.env.VITE_USE_POLLING === 'true' ? { watch: { usePolling: true, interval: 300 } } : undefined,
  test: {
    // 기본은 node — 순수 함수 테스트가 대부분이고 jsdom을 전역으로 켜면 그 전부가 느려진다.
    // 컴포넌트 테스트(.tsx)는 파일 상단 `// @vitest-environment jsdom` docblock으로 개별 전환한다 (G-4).
    environment: 'node',
    // tools/ 는 빌드타임 스크립트다(.mjs). 앱 코드가 아니라 tsc 대상이 아니지만, 판정 로직이
    // 들어 있어 회귀가 필요하다 — 그래서 테스트만 여기에 포함한다 (S15P21A604-480).
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'tools/**/*.test.mjs'],
  },
})
