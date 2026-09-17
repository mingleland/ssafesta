// @vitest-environment jsdom
// Tab 키 잠금 (S15P21A604-450, S15P21A604-838) — WorldPage가 활성화된 동안에는
// canvas·HUD·메뉴·입력창을 가리지 않고 브라우저의 Tab 기본 동작(포커스 이동)을 막는다.
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

describe('Tab 키 잠금 (-450, -838)', () => {
  it('canvas가 focus를 쥐면 Tab 기본 동작을 막는다', async () => {
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

  it('Game Menu가 열려 있어도 Web의 Tab 탐색을 막는다', async () => {
    await renderWorld();
    pressEscape();
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    expect(getWorldScreen()).toBe('menu');
    expect(dispatchTab(button)).toBe(true);
    button.remove();
  });

  it('월드 HUD의 DOM 버튼에서도 Tab 탐색을 막는다', async () => {
    await renderWorld();
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    expect(getWorldScreen()).toBe('world');
    expect(dispatchTab(button)).toBe(true);
    button.remove();
  });

  it('Web 입력창에서도 Tab 탐색을 막는다', async () => {
    await renderWorld();
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(dispatchTab(input)).toBe(true);
    input.remove();
  });

  it('Tab 이외의 키는 막지 않는다', async () => {
    await renderWorld();
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    act(() => { window.dispatchEvent(event); });
    expect(event.defaultPrevented).toBe(false);
  });

  it('WorldPage가 사라지면 Tab 차단도 해제한다', async () => {
    const view = await renderWorld();
    view.unmount();
    expect(dispatchTab(window)).toBe(false);
  });
});
