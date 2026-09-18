// React 앱을 브라우저 DOM에 최초 마운트하는 진입점
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { AppProviders } from './app/providers'
import { PersistentWorld } from './unity/host/PersistentWorld'
import { router } from './app/router'
import { installPreloadRecovery } from './shared/ui/preloadRecovery'

// 배포 뒤 옛 chunk 요청 실패 → 한 번만 새로고침 (S15P21A604-847). 근거는 preloadRecovery.ts 에.
installPreloadRecovery()

// 브라우저 우클릭 메뉴를 앱 전역에서 막는다 (2026-09-16).
//
// 월드가 화면 전체를 쓰는 제품이라 어디서 눌러도 "뒤로 · 새로고침 · 번역" 이 떠 버린다. 메뉴가 뜨면
// 그 위에 있는 동안 키 입력이 페이지에 닿지 않고, ESC 는 오버레이가 아니라 그 메뉴를 닫는 데 먹힌다.
//
// **Unity 의 오른쪽 버튼 입력은 그대로 산다.** 여기서 막는 것은 contextmenu 의 기본 동작뿐이고,
// Unity 가 읽는 것은 pointer·mouse 이벤트라 서로 다른 경로다.
//
// 리스너를 React 밖 진입점에 두는 이유는 범위 때문이다. 컴포넌트에 걸면 그 컴포넌트가 떠 있을 때만
// 막혀서 어디는 되고 어디는 안 되는 상태가 생긴다. 우클릭 붙여넣기도 함께 사라지는데, 그것을
// 감수하고 전역으로 막는 선택이다.
document.addEventListener('contextmenu', (event) => event.preventDefault())

// 브라우저 우클릭 메뉴를 앱 전역에서 막는다 (2026-09-16).
//
// 월드가 화면 전체를 쓰는 제품이라 어디서 눌러도 "뒤로 · 새로고침 · 번역" 이 떠 버린다. 메뉴가 뜨면
// 그 위에 있는 동안 키 입력이 페이지에 닿지 않고, ESC 는 오버레이가 아니라 그 메뉴를 닫는 데 먹힌다.
//
// **Unity 의 오른쪽 버튼 입력은 그대로 산다.** 여기서 막는 것은 contextmenu 의 기본 동작뿐이고,
// Unity 가 읽는 것은 pointer·mouse 이벤트라 서로 다른 경로다.
//
// 리스너를 React 밖 진입점에 두는 이유는 범위 때문이다. 컴포넌트에 걸면 그 컴포넌트가 떠 있을 때만
// 막혀서 어디는 되고 어디는 안 되는 상태가 생긴다. 우클릭 붙여넣기도 함께 사라지는데, 그것을
// 감수하고 전역으로 막는 선택이다.
document.addEventListener('contextmenu', (event) => event.preventDefault())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders>
      {/* 라우트 트리 밖 — 화면이 바뀌어도 Unity 가 살아남는 자리다 (S15P21A604-620) */}
      <PersistentWorld />
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
