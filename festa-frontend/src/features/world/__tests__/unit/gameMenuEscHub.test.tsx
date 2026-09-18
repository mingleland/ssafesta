// @vitest-environment jsdom
// ESC 메뉴 허브 편입 — 부스 관리(소유자만)·아바타 변경(회원만)·조작 안내
// (S15P21A604-798, 아바타 연결은 -852 / GitLab #197).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

const getMyBooth = vi.fn();
const openPanel = vi.fn();
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
const { setGuestSession } = await import('../../../auth/model/session');
const sessionManager = await import('../../../../unity/host/sessionManager');
const { __resetScreenAudioForTests } = await import('../../../audio/model/screenAudio');
const { __resetGameClientUiForTests } = await import('../../model/gameClientUi');
const { __resetProfileForTests } = await import('../../../profile/model/profile');

const onClose = vi.fn();

function renderMenu() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GameMenu onClose={onClose} onOpenPanel={openPanel} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  openPanel.mockReset();
  onClose.mockReset();
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
  vi.restoreAllMocks();
  __resetSessionForTests();
  __resetProfileForTests();
  __resetScreenAudioForTests();
});

describe('ESC 부스관리 항목', () => {
  it('부스가 없으면 항목이 아예 안 보인다', async () => {
    getMyBooth.mockResolvedValue(null);
    renderMenu();
    await waitFor(() => expect(getMyBooth).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: '부스 관리' })).toBeNull();
  });

  it('내 부스가 있으면 항목이 보이고, 누르면 관리 화면이 열린다', async () => {
    getMyBooth.mockResolvedValue({ boothId: 1, name: '테스트 부스', status: 'ACTIVE', lease: null });
    renderMenu();
    const button = await screen.findByRole('button', { name: '부스 관리' });
    fireEvent.click(button);
    const { getWorldScreen } = await import('../../model/worldScreen');
    expect(getWorldScreen()).toBe('management');
  });
});

// 게임 파트 수신부(WorldUiBridge.RequestAvatarCustomization)가 develop 에 도달해 스텁을 풀었다.
describe('ESC 아바타 변경', () => {
  it('회원이 누르면 Unity 에 요청하고 메뉴를 닫는다 — 아바타 화면을 덮지 않게', () => {
    const SendMessage = vi.fn();
    vi.spyOn(sessionManager, 'getReadyUnityInstance').mockReturnValue({ SendMessage } as never);
    renderMenu();

    fireEvent.click(screen.getByRole('button', { name: /아바타 변경/ }));

    expect(SendMessage).toHaveBeenCalledWith('WorldUiBridge', 'RequestAvatarCustomization', 'esc-menu');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('게스트에게는 항목이 아예 없다 — Unity 가 거부하므로 눌리는 버튼을 두지 않는다', () => {
    __resetSessionForTests();
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderMenu();

    expect(screen.queryByRole('button', { name: /아바타 변경/ })).toBeNull();
  });

  it('월드가 안 떠 있으면 아무 일도 하지 않는다 — 메뉴도 닫지 않는다', () => {
    vi.spyOn(sessionManager, 'getReadyUnityInstance').mockReturnValue(null);
    renderMenu();

    expect(() => fireEvent.click(screen.getByRole('button', { name: /아바타 변경/ }))).not.toThrow();
    expect(onClose).not.toHaveBeenCalled();
  });
});

// 패널 안에서 접었다 펴는 대신 자식 오버레이로 연다 — 메뉴 높이가 튀지 않고, 닫는 방법이
// ESC 하나로 통일된다.
describe('ESC 하위 화면 진입', () => {
  it('이용 안내·설정은 목록을 펼치지 않고 오버레이 요청만 보낸다', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: '이용 안내' }));
    expect(openPanel).toHaveBeenCalledWith('guide');
    expect(screen.queryByText('이동')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '설정' }));
    expect(openPanel).toHaveBeenCalledWith('settings');
    expect(screen.queryByRole('switch', { name: '음악' })).toBeNull();
  });

  it('회원이면 프로필 요약 행 자체가 내 정보 진입이다', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: '내 정보' }));
    expect(openPanel).toHaveBeenCalledWith('myInfo');
  });
});

// 일일 미션 (S15P21A604-859, GitLab #234)
describe('ESC 미션 항목', () => {
  it('회원이 누르면 미션 패널 요청만 보낸다', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: '미션' }));
    expect(openPanel).toHaveBeenCalledWith('missions');
  });

  it('게스트에게는 항목이 없다 — 수령이 403 MEMBER_ONLY 라 열어 놓고 전부 막는 화면이 된다', () => {
    __resetSessionForTests();
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderMenu();

    expect(screen.queryByRole('button', { name: '미션' })).toBeNull();
  });
});

// 메뉴를 닫으면 포커스가 복구된다 — OverlayFrame(-428)과 같은 규칙. 복구하지 않으면
// focus 가 body 에 남고 Unity 가 키를 못 받아 F 모달 뒤 ESC 메뉴를 거치면 F 가 죽는다.
describe('ESC 메뉴 포커스 복구', () => {
  it('닫으면 이전 요소(캔버스)로 포커스가 돌아간다', () => {
    const canvas = document.createElement('canvas');
    // 실제 Unity 캔버스는 tabIndex={-1} 로 포커스가 된다(-421) — 그대로 재현한다
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    expect(document.activeElement).toBe(canvas);

    const view = renderMenu();
    expect(document.activeElement).not.toBe(canvas);

    view.unmount();
    expect(document.activeElement).toBe(canvas);
    canvas.remove();
  });

  it('이전 요소가 사라졌으면 캔버스로 떨어진다', () => {
    const canvas = document.createElement('canvas');
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    const victim = document.createElement('button');
    document.body.appendChild(victim);
    victim.focus();

    const view = renderMenu();
    victim.remove();
    view.unmount();

    expect(document.activeElement).toBe(canvas);
    canvas.remove();
  });
});
