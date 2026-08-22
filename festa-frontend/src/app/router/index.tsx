// URL 경로와 화면을 연결하는 라우팅 규칙 정의
import { createBrowserRouter } from 'react-router-dom';
import { StudioPage } from '../../pages/studio/StudioPage';

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
  {
    // spec 005 — Owner/Staff Guard는 spec 001 인증 확정 후 추가(TODO)
    path: '/app/studio/:boothId',
    element: <StudioPage />,
  },
  {
    // spec 013a — Unity WebGL Host. 무거운 로더 코드를 메인 번들에서 뺀다(lazy)
    path: '/app/world',
    lazy: async () => {
      const { WorldPage } = await import('../../pages/world/WorldPage.tsx');
      return { Component: WorldPage };
    },
  },
  {
    path: '/app/games/:gameId/edit',
    lazy: async () => {
      const { EditGamePage } = await import('../../game-studio/app/routes/EditGamePage.tsx');
      return { Component: EditGamePage };
    },
  },
  {
    path: '/app/games/:gameId/play',
    lazy: async () => {
      const { PlayGamePage } = await import('../../game-studio/app/routes/PlayGamePage.tsx');
      return { Component: PlayGamePage };
    },
  },
]);
