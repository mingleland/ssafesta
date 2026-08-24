// URL 경로와 화면을 연결하는 라우팅 규칙 정의
import { createBrowserRouter } from 'react-router-dom';
import { StudioPage } from '../../pages/studio/StudioPage';
import { SlotListPage } from '../../pages/booth/SlotListPage';
import { LoginPage } from '../../pages/login/LoginPage';
import { CallbackPage } from '../../pages/auth/CallbackPage';
import { RequireAuth } from './RequireAuth';

// 경로 목록: docs/10_Frontend_설계서.md §3. 각 spec 착수 시 해당 경로 추가.
// 가드 등급(spec 001 plan.md §라우트 설계): 게스트 허용 = /app/home·/app/world(공개 화면·허용된
// 월드 둘러보기). 회원 전용 = /app/studio/:boothId 등 상태 변경 기능.
// /app/games/*는 plan.md 표에 명시가 없다 — 상태 변경(edit)·플레이(play) 모두 보수적으로
// member-only로 묶었다(모호성, 001 FE 구현 보고 참조). BE 계약·spec 확정 시 재분류.
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/auth/callback',
    element: <CallbackPage />,
  },
  {
    path: '/app/home',
    element: (
      <RequireAuth level="guest-allowed">
        <div>home</div>
      </RequireAuth>
    ),
  },
  {
    // spec 004 — 슬롯 조회는 공개 API라 guest-allowed. 임대 버튼은 페이지가 member만 연다(FR-016)
    path: '/app/booths',
    element: (
      <RequireAuth level="guest-allowed">
        <SlotListPage />
      </RequireAuth>
    ),
  },
  {
    path: '/app/studio/:boothId',
    element: (
      <RequireAuth level="member-only">
        <StudioPage />
      </RequireAuth>
    ),
  },
  {
    // spec 013a — Unity WebGL Host. 무거운 로더 코드를 메인 번들에서 뺀다(lazy)
    path: '/app/world',
    lazy: async () => {
      const { WorldPage } = await import('../../pages/world/WorldPage.tsx');
      return {
        Component: () => (
          <RequireAuth level="guest-allowed">
            <WorldPage />
          </RequireAuth>
        ),
      };
    },
  },
  {
    path: '/app/games/:gameId/edit',
    lazy: async () => {
      const { EditGamePage } = await import('../../game-studio/app/routes/EditGamePage.tsx');
      return {
        Component: () => (
          <RequireAuth level="member-only">
            <EditGamePage />
          </RequireAuth>
        ),
      };
    },
  },
  {
    path: '/app/games/:gameId/play',
    lazy: async () => {
      const { PlayGamePage } = await import('../../game-studio/app/routes/PlayGamePage.tsx');
      return {
        Component: () => (
          <RequireAuth level="member-only">
            <PlayGamePage />
          </RequireAuth>
        ),
      };
    },
  },
]);
