// @vitest-environment jsdom
// -271 회귀 방어 + -379 타이틀 화면 — 도메인 루트 `/` 는 게임 타이틀 화면(Landing)을 그린다.
// 영어 404("No route matches URL /")가 다시 생기지 않아야 하고, 화면 클릭 시 기존 가드
// semantics 그대로 흐른다: 비로그인 → /login(returnTo 저장), 세션 보유 → /app/world.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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
    expect(await screen.findByText('게스트로 둘러보기')).not.toBeNull();
  });

  it('게스트 세션이면 클릭 시 World 로 간다 (D-08 — 기본 상주 상태)', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAtRoot();
    fireEvent.click(screen.getByRole('button', { name: '화면을 클릭해 시작하기' }));
    // World 도착 판정은 HUD 조작 안내로 한다 — WorldSurface 는 aria-hidden 이라 잡히지 않는다
    expect(await screen.findByLabelText('조작 안내')).not.toBeNull();
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
    expect(await screen.findByLabelText('조작 안내')).not.toBeNull();
  });
});
