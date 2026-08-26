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
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
