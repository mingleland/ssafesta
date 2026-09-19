// @vitest-environment jsdom
// S15P21A604-115 회귀 방어 — /app/games/:gameId/play 라우트 가드가 guest-allowed로
// 재분류됐는지 확인한다. BE 계약(T087: Guest authoring 거부·Published play 허용) 확정에
// 따른 변경이다. anonymous는 guest-allowed 라우트에서도 여전히 /login으로 보내야 하고
// (guard.ts: 게스트 허용 라우트도 최소 게스트 토큰은 필요), /edit는 계속 member-only로
// 남아 있어야 한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { routes } from '../../index';
import {
  __resetSessionForTests,
  clearSession,
  markBootstrapped,
  setGuestSession,
  setMemberSession,
} from '../../../../features/auth/model/session';

afterEach(() => {
  cleanup();
  __resetSessionForTests();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderAt(path: string) {
  // 실제 라우터와 같은 정의를 쓴다 (rootRoute.test.tsx와 동일한 원칙) — 가드 결함이
  // 테스트만 통과하고 실제 앱엔 남는 것을 막는다.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
    </QueryClientProvider>,
  );
}

// PlayGamePage 진입 이후 PublishedGameSurface가 실제로 fetch를 쏘므로, 가드 통과 여부만
// 볼 때는 응답을 영원히 pending으로 둬서 로딩 화면 이상으로 못 나가게 고정한다.
function stubPendingFetch() {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
}

describe('/app/games/:gameId/play 라우트 가드 (S15P21A604-115)', () => {
  it('게스트 세션이면 로그인으로 리다이렉트되지 않는다 — guest-allowed', async () => {
    stubPendingFetch();
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('/app/games/1/play');

    expect(await screen.findByText('게시된 게임을 불러오는 중입니다.', {}, { timeout: 5000 })).not.toBeNull();
    expect(screen.queryByText('소셜 로그인 회원만 이용할 수 있는 기능입니다.')).toBeNull();
  });

  it('완전 비로그인(anonymous)이면 여전히 /login으로 보낸다 — guest-allowed도 최소 게스트 토큰은 필요', async () => {
    clearSession();
    markBootstrapped();
    renderAt('/app/games/1/play');

    expect(await screen.findByText('게스트로 둘러보기')).not.toBeNull();
  });

  it('정회원 세션이면 그대로 진입한다 (회귀 방어)', async () => {
    stubPendingFetch();
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('/app/games/1/play');

    expect(await screen.findByText('게시된 게임을 불러오는 중입니다.')).not.toBeNull();
  });

  it('/edit는 게스트로는 여전히 막힌다 — member-only 유지 (T087 Guest authoring 거부)', async () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('/app/games/1/edit');

    expect(await screen.findByText('소셜 로그인 회원만 이용할 수 있는 기능입니다.', {}, { timeout: 5000 })).not.toBeNull();
  });
});
