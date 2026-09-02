// @vitest-environment jsdom
// -271 회귀 방어 + -379 타이틀 화면 — 도메인 루트 `/` 는 게임 타이틀 화면(Landing)을 그린다.
// 영어 404("No route matches URL /")가 다시 생기지 않아야 하고, 화면 클릭 시 기존 가드
// semantics 그대로 흐른다: 비로그인 → /login(returnTo 저장), 세션 보유 → /app/home.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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

  it('게스트 세션이면 클릭 시 /app/home 으로 간다', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAtRoot();
    fireEvent.click(screen.getByRole('button', { name: '화면을 클릭해 시작하기' }));
    // /app/home 은 자리표시자 'home' 을 그린다 — 그것과 가드 헤더로 도착을 판정한다
    expect(await screen.findByText('home')).not.toBeNull();
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeNull();
  });
});
