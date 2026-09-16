// @vitest-environment jsdom
// ESC 메뉴 허브 편입 — 부스관리(소유자만)·아바타설정(스텁)·조작안내 (S15P21A604-798).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

const getMyBooth = vi.fn();
vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth() },
}));
vi.mock('../../../../entities/wallet/api.select', () => ({
  walletApi: { getWallet: vi.fn(async () => ({ userId: 1, balance: 100, updatedAt: new Date().toISOString() })) },
}));
vi.mock('../../../../entities/user/api.select', () => ({
  userApi: {
    getMe: vi.fn(async () => ({ userId: 1, nickname: '테스트유저', providers: ['google'], avatarCode: null })),
  },
}));

// vi.mock 팩토리가 먼저 등록된 뒤 소비자를 불러온다 — top-level const의 초기화 전 접근을 피한다.
const { GameMenu } = await import('../../ui/GameMenu');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import('../../../auth/model/session');
const { __resetScreenAudioForTests } = await import('../../../audio/model/screenAudio');
const { __resetGameClientUiForTests } = await import('../../model/gameClientUi');
const { __resetProfileForTests } = await import('../../../profile/model/profile');

function renderMenu() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GameMenu onClose={() => {}} onOpenMyInfo={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  __resetSessionForTests();
  __resetProfileForTests();
  __resetScreenAudioForTests();
  __resetGameClientUiForTests();
  getMyBooth.mockReset();
  getMyBooth.mockResolvedValue(null);
  setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  __resetSessionForTests();
  __resetProfileForTests();
  __resetScreenAudioForTests();
});

describe('ESC 부스관리 항목', () => {
  it('부스가 없으면 항목이 아예 안 보인다', async () => {
    getMyBooth.mockResolvedValue(null);
    renderMenu();
    await waitFor(() => expect(getMyBooth).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: '부스관리' })).toBeNull();
  });

  it('내 부스가 있으면 항목이 보이고, 누르면 관리 화면이 열린다', async () => {
    getMyBooth.mockResolvedValue({ boothId: 1, name: '테스트 부스', status: 'ACTIVE', lease: null });
    renderMenu();
    const button = await screen.findByRole('button', { name: '부스관리' });
    fireEvent.click(button);
    const { getWorldScreen } = await import('../../model/worldScreen');
    expect(getWorldScreen()).toBe('management');
  });
});

describe('ESC 아바타설정 스텁', () => {
  it('누를 수 없고 준비 중 배지가 있다 — 진입점 미정', () => {
    renderMenu();
    const button = screen.getByRole('button', { name: /아바타설정/ });
    expect(button.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('준비 중')).toBeTruthy();
  });
});

describe('ESC 조작안내 항목', () => {
  it('열면 조작 목록이 나오고, 다시 누르면 닫힌다', () => {
    renderMenu();
    const button = screen.getByRole('button', { name: '조작안내' });
    expect(screen.queryByText('이동')).toBeNull();

    fireEvent.click(button);
    expect(screen.getAllByText('이동').length).toBeGreaterThan(0);

    fireEvent.click(button);
    expect(screen.queryByText('이동')).toBeNull();
  });
});
