// URL 경로와 화면을 연결하는 라우팅 규칙 정의
import { Navigate, createBrowserRouter } from 'react-router-dom';
import { LandingPage } from '../../pages/landing/LandingPage';
import { ProfilePage } from '../../pages/profile/ProfilePage';
import { StudioPage } from '../../pages/studio/StudioPage';
import { SlotListPage } from '../../pages/booth/SlotListPage';
import { ProjectManagementPage } from '../../pages/management/ProjectManagementPage';
import { SurveyManagementPage } from '../../pages/management/SurveyManagementPage';
import { ConsultationStaffPage } from '../../pages/management/ConsultationStaffPage';
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
    // 지우지는 않는다. 외부 링크·북마크와 Game Studio(protected, GameStudioShell 의 홈 버튼)가
    // 아직 이 경로를 가리킨다. 가드를 걸지 않는 이유: 목적지인 /app/world 가 같은 등급의
    // 가드를 이미 갖고 있어 여기서 한 번 더 판정하면 redirect 가 두 번 일어난다.
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
    // spec 004 — 슬롯 조회는 공개 API라 guest-allowed. 임대 버튼은 페이지가 member만 연다(FR-016)
    path: '/app/booths',
    element: (
      <RequireAuth level="guest-allowed">
        <SlotListPage />
      </RequireAuth>
    ),
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
          <RequireAuth level="guest-allowed">
            <PlayGamePage />
          </RequireAuth>
        ),
      };
    },
  },
];

export const router = createBrowserRouter(routes);
