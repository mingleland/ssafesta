// @vitest-environment jsdom
// Tab 키 잠금 (S15P21A604-450) — 브라우저 기본 동작(포커스 이동)이 Unity 캔버스 focus 를
// 빼앗으면 Unity 자신의 Tab 미니맵 토글이 keydown 을 못 받는다. 월드가 주인이고 실제 canvas가
// 포커스를 쥔 경우에만 브라우저 기본 동작을 막는다. HUD·오버레이 등 DOM 요소의 Tab 탐색은 보존한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { closeOverlay } from '../../../../shared/types/overlay';
import { __resetGameClientUiForTests } from '../../../../features/world/model/gameClientUi';
import { getWorldScreen } from '../../../../features/world/model/worldScreen';
import { __resetWorldUiStateForTests } from '../../../../unity/bridge/worldUiState';

const getReadyUnityInstance = vi.fn(() => null);
vi.mock('../../../../unity/host/sessionManager', () => ({
  getReadyUnityInstance: () => getReadyUnityInstance(),
}));
vi.mock('../../../../unity/host/worldUiBridge', () => ({ requestExitWorldUi: vi.fn() }));
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: true,
  WorldSurface: () => <div data-testid="world-surface" />,
}));
vi.mock('../../../../features/overlay/OverlayHost', () => ({ OverlayHost: () => null }));
vi.mock('../../../../features/booth/ui/BoothManagementOverlay', () => ({
  BoothManagementOverlay: () => <div data-testid="management" />,
}));
vi.mock('../../../../features/booth/ui/ManagementPanelHost', () => ({
  ManagementPanelHost: () => <div data-testid="management-panel" />,
}));
vi.mock('../../../../features/world/ui/WorldHud', () => ({ WorldHud: () => null }));
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => <div data-testid="game-menu" /> }));

function dispatchTab(target: EventTarget): boolean {
  const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  act(() => { target.dispatchEvent(event); });
  return event.defaultPrevented;
}

const pressEscape = () =>
  act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); });

beforeEach(() => {
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});
afterEach(() => {
  cleanup();
  document.getElementById('unity-canvas')?.remove();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});

async function renderWorld() {
  const { WorldPage } = await import('../../WorldPage');
  return render(
    <MemoryRouter initialEntries={['/app/world']}>
      <WorldPage />
    </MemoryRouter>,
  );
}

describe('Tab 키 잠금 (-450)', () => {
  it('월드가 주인이고 canvas가 focus를 쥐면 Tab 기본 동작을 막는다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    expect(getWorldScreen()).toBe('world');
    expect(document.activeElement).toBe(canvas);
    expect(dispatchTab(canvas)).toBe(true);
    canvas.remove();
  });

  it('Game Menu 가 열려 있으면 막지 않는다 — 메뉴 안에서는 Tab 으로 항목 이동이 정상이다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    pressEscape();
    expect(getWorldScreen()).toBe('menu');
    expect(dispatchTab(canvas)).toBe(false);
    canvas.remove();
  });

  it('월드가 주인이어도 DOM 버튼의 Tab 탐색은 막지 않는다', async () => {
    await renderWorld();
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    expect(dispatchTab(button)).toBe(false);
    button.remove();
  });
});
