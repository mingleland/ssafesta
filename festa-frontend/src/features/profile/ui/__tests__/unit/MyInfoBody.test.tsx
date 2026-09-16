// @vitest-environment jsdom
// MyInfoBody — ProfilePage·MyInfoOverlay가 공유하는 내 정보 본문 (S15P21A604-798).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../../../../entities/user/api.select', () => ({
  userApi: {
    getMe: vi.fn(async () => ({
      userId: 1,
      nickname: '테스트유저',
      providers: ['google'],
      avatarCode: null,
    })),
  },
}));
vi.mock('../../../../../entities/wallet/api.select', () => ({
  walletApi: {
    getWallet: vi.fn(async () => ({ userId: 1, balance: 100, updatedAt: new Date().toISOString() })),
    getTransactions: vi.fn(async () => ({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
  },
}));

const { MyInfoBody } = await import('../../MyInfoBody');
const { __resetProfileForTests } = await import('../../../model/profile');
const { __resetSessionForTests, setMemberSession, markBootstrapped } = await import('../../../../auth/model/session');

function renderBody() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MyInfoBody />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  __resetSessionForTests();
  __resetProfileForTests();
  setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
  __resetProfileForTests();
});

describe('MyInfoBody', () => {
  it('닉네임과 보유 코인을 그린다 — PageShell 없이도', async () => {
    renderBody();
    await waitFor(() => {
      expect(screen.getByText('테스트유저')).toBeTruthy();
      expect(screen.getByText(/100/)).toBeTruthy();
    });
  });
});
