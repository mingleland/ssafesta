// URL 경로와 화면을 연결하는 라우팅 규칙 정의
import { Navigate, createBrowserRouter } from 'react-router-dom';
import { LandingPage } from '../../pages/landing/LandingPage';
import { ProfilePage } from '../../pages/profile/ProfilePage';
import { ProjectManagementPage } from '../../pages/management/ProjectManagementPage';
import { SurveyManagementPage } from '../../pages/management/SurveyManagementPage';
import { ConsultationStaffPage } from '../../pages/management/ConsultationStaffPage';
import { AiAgentManagementPage } from '../../pages/management/AiAgentManagementPage';
import { LoginPage } from '../../pages/login/LoginPage';
import { CallbackPage } from '../../pages/auth/CallbackPage';
import { RequireAuth } from './RequireAuth';

// 경로 목록: docs/10_Frontend_설계서.md §3. 각 spec 착수 시 해당 경로 추가.
// 가드 등급(spec 001 plan.md §라우트 설계): 게스트 허용 = /app/world(허용된 월드 둘러보기).
// 회원 전용 = /app/studio/:boothId 등 상태 변경 기능.
// /app/games/*는 plan.md 표에 명시가 없어 초기엔 edit·play 모두 보수적으로 member-only로
// 묶었다(모호성, 001 FE 구현 보고 참조). play는 spec 019 BE 계약(T087: Guest authoring
// 거부·Published play 허용)이 확정되며 guest-allowed로 재분류했다(S15P21A604-115).
// edit(저작)은 T087 그대로 member-only 유지.
// 라우트 정의를 배열로 분리해 둔다 — createMemoryRouter 로 같은 정의를 테스트에서 쓴다.
export const routes = [
  {
    // 도메인 루트 = 게임 타이틀 화면 (S15P21A604-379). 시작 클릭의 목적지는 World 다 —
    // 로그인 후 사용자가 상주하는 기본 상태이기 때문이다(D-08). 인증 판정은 그대로 가드에
    // 위임한다: /app/world 는 guest-allowed 라 비로그인이면 RequireAuth 가 /login 으로 보내고
    // returnTo 도 저장한다.
    path: '/',
    element: <LandingPage />,
  },
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/auth/callback',
    element: <CallbackPage />,
  },
  {
    // 구 진입 허브. 제품 Home 은 없어졌다(D-08 — World 가 기본 상주 상태) — 다만 경로를
    // 지우지는 않는다. 외부 링크·북마크가 아직 이 경로를 가리킬 수 있다(Game Studio의 뒤로가기는
    // S15P21A604-824부터 /app/games를 가리켜 더 이상 이 경로를 쓰지 않는다). 가드를 걸지 않는
    // 이유: 목적지인 /app/world 가 같은 등급의 가드를 이미 갖고 있어 여기서 한 번 더 판정하면
    // redirect 가 두 번 일어난다.
    path: '/app/home',
    element: <Navigate to="/app/world" replace />,
  },
  {
    // 내 정보 — 계정 조회(users/me)는 회원 전용이다
    path: '/app/profile',
    element: (
      <RequireAuth level="member-only">
        <ProfilePage />
      </RequireAuth>
    ),
  },
  {
    // spec 004 — 임대는 2026-09-17 부터 **월드 위 오버레이**다. 옛 전체 페이지는 route 를 갈아타
    // 월드를 떠났고, 그때마다 Unity 가 언마운트됐다. 옛 주소는 살려 두고 오버레이로 보낸다.
    path: '/app/booths',
    element: <Navigate to="/app/world?panel=rental" replace />,
  },
  {
    // Booth Management 하위 상세 화면 3종 — 전부 소유자 전용 상태 변경 기능이라 member-only.
    // Booth 소유자 판정은 각 화면의 데이터 호출에서 서버가 최종 결정한다(가드는 UX 보조).
    path: '/app/booths/:boothId/project',
    element: (
      <RequireAuth level="member-only">
        <ProjectManagementPage />
      </RequireAuth>
    ),
  },
  {
    path: '/app/booths/:boothId/survey',
    element: (
      <RequireAuth level="member-only">
        <SurveyManagementPage />
      </RequireAuth>
    ),
  },
  {
    path: '/app/booths/:boothId/consultation',
    element: (
      <RequireAuth level="member-only">
        <ConsultationStaffPage />
      </RequireAuth>
    ),
  },
  {
    path: '/app/booths/:boothId/ai-agent',
    element: (
      <RequireAuth level="member-only">
        <AiAgentManagementPage />
      </RequireAuth>
    ),
  },
  {
    // Booth Studio 는 폐기됐다 (2026-09-17, 편집기 코드 삭제 S15P21A604-846). 옛 주소는 북마크·외부
    // 링크가 404 로 떨어지지 않게 월드로 보낸다. 부스 런타임을 만드는 자동화(assets:build·manifest·
    // ReloadBoothSlot)는 그대로다. 테마·대표색·간판·로고 편집이 다시 필요해지면 Studio 를 복원하지 않고
    // Booth Management 에서 새로 짠다.
    path: '/app/studio/:boothId',
    element: <Navigate to="/app/world" replace />,
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
    // 내 게임 목록·생성 — S15P21A604-824. 진입점(어디서 이 경로로 오는지)은 아직 미정이라
    // 지금은 URL 직접 접근으로만 열린다.
    path: '/app/games',
    lazy: async () => {
      const { GamesListPage } = await import('../../game-studio/app/routes/GamesListPage.tsx');
      return {
        Component: () => (
          <RequireAuth level="member-only">
            <GamesListPage />
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
          <RequireAuth level="guest-allowed">
            <PlayGamePage />
          </RequireAuth>
        ),
      };
    },
  },
  {
    // 관리자 콘솔은 화면이 아니라 월드 위 오버레이다 (S15P21A604-828) — 이 서비스는 월드가 상주
    // 화면이고 나머지는 그 위에 잠깐 뜬다. 경로를 지우지 않는 이유는 북마크·옛 링크가 404 로
    // 떨어지지 않게 하기 위해서다. 목적지에서 WorldPage 가 `?panel` 을 읽어 오버레이를 연다.
    path: '/app/admin/*',
    element: <Navigate to="/app/world?panel=admin" replace />,
  },
];

export const router = createBrowserRouter(routes);
