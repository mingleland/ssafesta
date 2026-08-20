// URL 경로와 화면을 연결하는 라우팅 규칙 정의
import { createBrowserRouter } from 'react-router-dom';

// 경로 목록: docs/10_Frontend_설계서.md §3. 각 spec 착수 시 해당 경로 추가.
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <div>login — spec 001에서 구현</div>,
  },
  {
    path: '/app/home',
    element: <div>home</div>,
  },
]);
