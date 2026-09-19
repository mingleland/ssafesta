// @vitest-environment jsdom
// 조작 안내 진입 두 갈래 — HUD 우하단 버튼은 'guide' 패널을, ESC 이용 안내는 안내 가이드
// (WORLD_GUIDE) 오버레이를 연다. 둘이 뒤바뀌면 조작표와 환영 안내가 서로 자리에 나타난다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { WorldHud } from '../../WorldHud';
import { GameMenu } from '../../GameMenu';
import {
  __resetGameClientUiForTests,
  getGameClientUiSnapshot,
  openGameMenu,
} from '../../../model/gameClientUi';
import { getWorldScreen } from '../../../model/worldScreen';
import { closeOverlay, getCurrentOverlay } from '../../../../../shared/types/overlay';
import {
  __resetSessionForTests,
  markBootstrapped,
  setMemberSession,
} from '../../../../auth/model/session';

vi.mock('../../../../../entities/wallet/api.select', () => ({
  walletApi: { getWallet: () => Promise.resolve({ balance: 70 }) },
}));
vi.mock('../../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => Promise.resolve(null), getSlots: () => Promise.resolve([]) },
}));
vi.mock('../../../../../entities/admin/api.select', () => ({
  adminApi: { getCapability: () => Promise.resolve({ admin: false }) },
}));

afterEach(() => {
  cleanup();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetSessionForTests();
});

describe('HUD 조작 안내 버튼', () => {
  it('우하단 동일 규격 버튼이 guide 패널을 연다', () => {
    render(<WorldHud />);
    const button = screen.getByRole('button', { name: '조작 안내' });
    // 전체화면과 같은 규격을 쓴다 — 자리만 우하단이다
    expect(button.className).toContain('world-hud-fullscreen');
    expect(button.className).toContain('world-hud-guide');
    fireEvent.click(button);
    expect(getGameClientUiSnapshot().menuPanel).toBe('guide');
    expect(getWorldScreen()).toBe('menuPanel');
  });
});

describe('ESC 이용 안내', () => {
  it('안내 가이드 오버레이를 열고 메뉴는 걷는다', () => {
    setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    openGameMenu();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/app/world']}>
          <GameMenu onClose={() => {}} onOpenPanel={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: '이용 안내' }));
    expect(getCurrentOverlay()?.type).toBe('WORLD_GUIDE');
    expect(getGameClientUiSnapshot().gameMenu).toBe(false);
  });
});
