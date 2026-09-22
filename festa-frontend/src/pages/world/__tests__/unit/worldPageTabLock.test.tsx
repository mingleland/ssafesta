// @vitest-environment jsdom
// Tab 키 잠금 (S15P21A604-450 · S15P21A604-838).
//
// 차단은 화면을 가리지 않는다 — WorldPage 가 떠 있는 동안 canvas·HUD·메뉴·입력창 어디서든
// 브라우저의 Tab 기본 동작(포커스 이동)이 없다.
// 포커스 복귀는 월드가 주인일 때만이다 — 그래야 다음 Tab 을 Unity 가 받는다. 오버레이·메뉴가
// 주인이거나 텍스트 입력 중이면 막기만 하고 포커스는 그대로 둔다.
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
  const result = render(
    <MemoryRouter initialEntries={['/app/world']}>
      <WorldPage />
    </MemoryRouter>,
  );
  // 진입 환영 안내(-599)를 걷고 시작한다 — 떠 있으면 월드가 주인이 아니라 Tab 판정이 달라진다.
  act(() => { closeOverlay(); });
  return result;
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

  it('월드 HUD 버튼에서도 막고, 월드가 주인이면 캔버스로 돌려준다 — 다음 Tab 부터 Unity 가 받는다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    expect(getWorldScreen()).toBe('world');
    expect(dispatchTab(button)).toBe(true);
    expect(document.activeElement).toBe(canvas);
    button.remove();
    canvas.remove();
  });

  it('텍스트 입력 중에는 막기만 하고 포커스를 뺏지 않는다 — 채팅을 치다 끌려가면 안 된다', async () => {
    await renderWorld();
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(dispatchTab(input)).toBe(true);
    expect(document.activeElement).toBe(input);
    input.remove();
    canvas.remove();
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
