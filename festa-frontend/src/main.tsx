// React 앱을 브라우저 DOM에 최초 마운트하는 진입점
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { AppProviders } from './app/providers'
import { PersistentWorld } from './unity/host/PersistentWorld'
import { router } from './app/router'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders>
      {/* 라우트 트리 밖 — 화면이 바뀌어도 Unity 가 살아남는 자리다 (S15P21A604-620) */}
      <PersistentWorld />
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
