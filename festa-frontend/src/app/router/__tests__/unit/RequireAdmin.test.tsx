// @vitest-environment jsdom
// RequireAdmin — "관리자인가" 는 서버가 주는 값이고 FE 는 그 답이 오기 전에 화면을 열지도 막지도 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const getCapability = vi.fn();
vi.mock('../../../../entities/admin/api.select', () => ({ adminApi: { getCapability: () => getCapability() } }));

const { RequireAdmin } = await import('../../RequireAdmin');
const { __resetSessionForTests, markBootstrapped, setGuestSession, setMemberSession } = await import('../../../../features/auth/model/session');

function renderAt() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/app/admin/overview']}>
        <Routes>
          <Route path="/app/admin/:section" element={<RequireAdmin><p>콘솔 본문</p></RequireAdmin>} />
          <Route path="/login" element={<p>로그인 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => { getCapability.mockReset(); __resetSessionForTests(); });
afterEach(() => cleanup());

describe('RequireAdmin', () => {
  it('관리자면 본문을 연다', async () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    getCapability.mockResolvedValue({ admin: true, master: false });
    renderAt();
    expect(screen.queryByText('관리자 권한을 확인하고 있어요...')).not.toBeNull();
    await waitFor(() => expect(screen.queryByText('콘솔 본문')).not.toBeNull());
  });

  it('회원이지만 관리자가 아니면 막고 이유를 말한다', async () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    getCapability.mockResolvedValue({ admin: false, master: false });
    renderAt();
    await waitFor(() => expect(screen.queryByText('관리자만 이용할 수 있는 화면입니다.')).not.toBeNull());
    expect(screen.queryByText('콘솔 본문')).toBeNull();
  });

  it('게스트는 RequireAuth 가 먼저 막고 capability 를 묻지 않는다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt();
    expect(screen.queryByText('소셜 로그인 회원만 이용할 수 있는 기능입니다.')).not.toBeNull();
    expect(getCapability).not.toHaveBeenCalled();
  });

  it('권한 확인이 실패하면 다시 시도할 수 있고, 관리자로 가정하지 않는다', async () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    getCapability.mockRejectedValue(new TypeError('Failed to fetch'));
    renderAt();
    await waitFor(() => expect(screen.queryByText('관리자 권한을 확인하지 못했습니다.')).not.toBeNull());
    expect(screen.queryByText('콘솔 본문')).toBeNull();
  });
});

