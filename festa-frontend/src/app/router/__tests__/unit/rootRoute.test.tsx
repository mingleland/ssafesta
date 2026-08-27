// @vitest-environment jsdom
// -271 회귀 방어 — 도메인 루트 `/` 에 라우트가 없어 React Router 기본 ErrorBoundary 가
// 영어 404("No route matches URL /")를 그리던 결함. 주소창에 도메인만 친 첫 방문자가
// 보던 화면이라 사용자 영향이 있다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
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
  return render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />);
}

describe('루트 경로 `/`', () => {
  it('비로그인 방문자는 영어 404 대신 로그인 화면으로 간다', async () => {
    markBootstrapped();
    renderAtRoot();
    expect(await screen.findByText('게스트로 둘러보기')).not.toBeNull();
    expect(document.body.textContent).not.toContain('No route matches');
  });

  it('게스트 세션이면 /app/home 으로 간다', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAtRoot();
    // /app/home 은 자리표시자 'home' 을 그린다 — 그것과 가드 헤더로 도착을 판정한다
    expect(await screen.findByText('home')).not.toBeNull();
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeNull();
  });
});
