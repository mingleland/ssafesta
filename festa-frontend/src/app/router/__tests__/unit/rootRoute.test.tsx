// @vitest-environment jsdom
// -271 회귀 방어 + -379 타이틀 화면 — 도메인 루트 `/` 는 게임 타이틀 화면(Landing)을 그린다.
// 영어 404("No route matches URL /")가 다시 생기지 않아야 하고, 화면 클릭 시 기존 가드
// semantics 그대로 흐른다: 비로그인 → /login(returnTo 저장), 세션 보유 → /app/world.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { routes } from '../../index';
import {
  __resetSessionForTests,
  markBootstrapped,
  setGuestSession,
} from '../../../../features/auth/model/session';

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

/**
 * 이 파일의 `findBy*` 는 **lazy 라우트 경계를 넘어 기다린다** (S15P21A604-550).
 *
 * 클릭 → `navigate('/app/world')` → `/app/world` 의 lazy chunk 해석 → 그제서야 가드가
 * `/login` 으로 되돌리거나 World 가 뜬다. 그 dynamic import 는 전체 스위트에서 1초를 넘긴다
 * (실측 1058·1066·1098ms). testing-library 기본값이 1000ms 라 **단독 실행은 통과하고
 * 전체 스위트에서만 red** 가 됐다 — 코드 결함이 아니라 이 파일의 대기 예산이 짧았던 것이다.
 *
 * 재는 것은 라우팅 semantics 지 로딩 속도가 아니다. 속도를 지키고 싶으면 그것을 재는
 * 테스트를 따로 둔다 — 여기서 겸하면 무엇이 깨졌는지 알 수 없어진다.
 */
const LAZY_ROUTE_TIMEOUT = { timeout: process.env.CI ? 15_000 : 5_000 };

/**
 * World 도착 판정 — **HUD 로 재지 않는다** (S15P21A604-613).
 *
 * `WorldHud` 는 Unity 가 실제로 월드에 들어간 뒤(`hostPhase === 'ready'`)에야 뜬다. 캐릭터
 * 선택 화면 위에 "W A S D 이동" 이 겹쳐 뜨던 것을 막은 변경이다. 테스트 환경에는 Unity 가
 * 없으니 그 상태에 도달하지 않고, mock 월드에서만 HUD 가 보인다 — 판정에 쓰면 `VITE_USE_MOCK`
 * 값에 따라 red/green 이 갈린다.
 *
 * 여기서 재는 것은 라우팅 semantics 다. 화면 컨테이너가 떴으면 World 라우트가 해석된 것이고,
 * 그 안에서 무엇이 언제 뜨는지는 `worldHudPhaseGate` 가 따로 잰다.
 */
async function arrivedAtWorld() {
  await waitFor(() => {
    expect(document.querySelector('.world-scene')).not.toBeNull();
  }, LAZY_ROUTE_TIMEOUT);
}

function renderAtRoot() {
  // 실제 라우터와 같은 정의를 쓴다 — 테스트용으로 별도 라우트를 다시 적으면 결함이
  // 그대로 남은 채 테스트만 통과한다
  // 실제 앱과 같은 provider 구성 — 하위 화면들이 React Query 를 쓴다
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />
    </QueryClientProvider>,
  );
}

describe('루트 경로 `/`', () => {
  it('타이틀 화면을 그린다 — 영어 404 가 아니다 (-271 회귀 방어)', () => {
    markBootstrapped();
    renderAtRoot();
    expect(screen.getByRole('button', { name: '화면을 클릭해 시작하기' })).not.toBeNull();
    expect(document.body.textContent).not.toContain('No route matches');
  });

  it('비로그인 방문자가 화면을 클릭하면 로그인 화면으로 간다', async () => {
    markBootstrapped();
    renderAtRoot();
    fireEvent.click(screen.getByRole('button', { name: '화면을 클릭해 시작하기' }));
    expect(await screen.findByText('게스트로 둘러보기', {}, LAZY_ROUTE_TIMEOUT)).not.toBeNull();
  });

  it('게스트 세션이면 클릭 시 World 로 간다 (D-08 — 기본 상주 상태)', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAtRoot();
    fireEvent.click(screen.getByRole('button', { name: '화면을 클릭해 시작하기' }));
    await arrivedAtWorld();
    // 계정 칩·로그아웃은 World 에 없다(D-08) — ESC Game Menu 소관
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull();
  });

  it('/app/home 은 제품 화면이 아니라 World 로 보내는 호환 경로다', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/app/home'] })} />
      </QueryClientProvider>,
    );
    await arrivedAtWorld();
  });
});
