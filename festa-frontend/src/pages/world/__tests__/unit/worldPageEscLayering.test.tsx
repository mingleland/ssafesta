// @vitest-environment jsdom
// ESC 계층 (S15P21A604-450) — "떠 있으면 하나 닫고, 없으면 메뉴를 연다".
//
// 예전에는 이 화면이 두 store 를 각각 읽어 우선순위를 손으로 합성했다. 그 판정을 worldScreen 으로
// 옮겼으므로, 여기서 잠그는 것은 "WorldPage 가 그 계층을 통해 동작한다" 는 배선이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { closeOverlay, getCurrentOverlay, openOverlay } from '../../../../shared/types/overlay';
import {
  __resetGameClientUiForTests,
  getGameClientUiSnapshot,
  openBoothManagement,
} from '../../../../features/world/model/gameClientUi';
import { getWorldScreen } from '../../../../features/world/model/worldScreen';

// Unity·오버레이 내용물은 이 테스트의 관심사가 아니다 — ESC 배선만 본다.
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: true,
  WorldSurface: () => <div data-testid="world-surface" />,
}));
vi.mock('../../../../features/overlay/OverlayHost', () => ({ OverlayHost: () => null }));
vi.mock('../../../../features/booth/ui/BoothManagementOverlay', () => ({
  BoothManagementOverlay: () => <div data-testid="management" />,
}));
vi.mock('../../../../features/world/ui/WorldHud', () => ({ WorldHud: () => null }));
// GameMenu 는 프로필·지갑 쿼리를 끌고 온다 — ESC 배선 테스트에 QueryClientProvider 를 세우지 않는다.
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => <div data-testid="game-menu" /> }));

const pressEscape = () =>
  act(() => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  });

beforeEach(() => {
  closeOverlay();
  __resetGameClientUiForTests();
});
afterEach(() => {
  cleanup();
  closeOverlay();
  __resetGameClientUiForTests();
});

async function renderWorld() {
  const { WorldPage } = await import('../../WorldPage');
  return render(
    <MemoryRouter initialEntries={['/app/world']}>
      <WorldPage />
    </MemoryRouter>,
  );
}

describe('WorldPage ESC 계층 (-450)', () => {
  it('아무것도 없으면 Game Menu 를 연다 — ESC 는 나/시스템이다', async () => {
    await renderWorld();
    pressEscape();
    expect(getWorldScreen()).toBe('menu');
  });

  it('Game Menu 가 열려 있으면 닫고 월드로 돌아온다', async () => {
    await renderWorld();
    pressEscape();
    pressEscape();
    expect(getWorldScreen()).toBe('world');
  });

  it('관리 화면이 떠 있으면 그것을 닫는다 — 메뉴를 덧열지 않는다', async () => {
    await renderWorld();
    act(() => { openBoothManagement(); });
    pressEscape();
    expect(getGameClientUiSnapshot().managementOverlay).toBe(false);
    expect(getWorldScreen()).toBe('world');
  });

  it('Visitor Overlay 가 떠 있으면 그것을 닫는다', async () => {
    await renderWorld();
    act(() => { openOverlay('LAPTOP', { boothId: 1 }); });
    pressEscape();
    expect(getCurrentOverlay()).toBeNull();
    expect(getWorldScreen()).toBe('world');
  });

  it('한 번에 하나씩 닫는다 — ESC 한 번이 여러 레이어를 걷지 않는다', async () => {
    await renderWorld();
    // 배타를 우회해 둘이 켜진 상태(#139 가 보고한 그 상태)에서도 계단식으로 닫힌다
    act(() => {
      openOverlay('LAPTOP', { boothId: 1 });
      openBoothManagement();
    });
    pressEscape();
    expect(getWorldScreen()).toBe('management');
    pressEscape();
    expect(getWorldScreen()).toBe('world');
  });
});
