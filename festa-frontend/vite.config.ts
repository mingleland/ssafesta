import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 컨테이너 dev 타깃에서만 켠다 — Windows bind mount 는 inotify 이벤트를 컨테이너로 전달하지
  // 않아 HMR 이 조용히 죽는다(느린 게 아니라 아예 안 온다). polling 은 CPU 를 계속 쓰므로
  // 호스트 실행(기본 경로)에서는 끈 채로 둔다. Dockerfile 의 dev 타깃이 이 값을 세운다.
  server: process.env.VITE_USE_POLLING === 'true' ? { watch: { usePolling: true, interval: 300 } } : undefined,
  test: {
    // 기본은 node — 순수 함수 테스트가 대부분이고 jsdom을 전역으로 켜면 그 전부가 느려진다.
    // 컴포넌트 테스트(.tsx)는 파일 상단 `// @vitest-environment jsdom` docblock으로 개별 전환한다 (G-4).
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
