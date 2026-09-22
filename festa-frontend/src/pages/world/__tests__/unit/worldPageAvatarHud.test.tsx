// @vitest-environment jsdom
// 아바타 변경 화면(Unity) 중 FE HUD 숨김 (S15P21A604-852).
// 판정은 avatar 플래그 하나다 — focus/minigame 은 묶지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { closeOverlay } from '../../../../shared/types/overlay';
import { __resetGameClientUiForTests } from '../../../../features/world/model/gameClientUi';
import { __resetSessionForTests, setMemberSession } from '../../../../features/auth/model/session';
import { __resetWorldChatForTests, getWorldChatSnapshot } from '../../../../features/worldChat/model/worldChat';
import { __resetWorldUiStateForTests, applyWorldUiStateJson, resetWorldUiState } from '../../../../unity/bridge/worldUiState';

vi.mock('../../../../unity/host/sessionManager', () => ({ getReadyUnityInstance: () => ({ SendMessage: vi.fn() }) }));
vi.mock('../../../../unity/host/worldUiBridge', () => ({ requestExitWorldUi: vi.fn() }));
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({ IS_MOCK_WORLD: true, WorldSurface: () => <div /> }));
vi.mock('../../../../features/overlay/OverlayHost', () => ({ OverlayHost: () => null }));
vi.mock('../../../../features/booth/ui/BoothManagementOverlay', () => ({ BoothManagementOverlay: () => null }));
vi.mock('../../../../features/booth/ui/ManagementPanelHost', () => ({ ManagementPanelHost: () => null }));
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => null }));
vi.mock('../../../../features/world/ui/MenuPanelHost', () => ({ MenuPanelHost: () => null }));
vi.mock('../../../../features/world/ui/WorldHud', () => ({ WorldHud: () => <div data-testid='world-hud' /> }));
vi.mock('../../../../features/worldChat/ui/WorldChatLayer', () => ({ WorldChatLayer: () => <div data-testid='world-chat' /> }));

beforeEach(() => { closeOverlay(); __resetGameClientUiForTests(); __resetWorldUiStateForTests(); __resetWorldChatForTests(); __resetSessionForTests(); });
afterEach(() => { cleanup(); closeOverlay(); __resetGameClientUiForTests(); __resetWorldUiStateForTests(); __resetWorldChatForTests(); __resetSessionForTests(); });

async function renderWorld() {
  const { WorldPage } = await import('../../WorldPage');
  const result = render(<MemoryRouter initialEntries={['/app/world']}><WorldPage /></MemoryRouter>);
  // 진입 환영 안내(-599)를 걷고 시작한다 — 이 파일이 재는 것은 그 뒤의 계층이다.
  act(() => { closeOverlay(); });
  return result;
}

const setUi = (patch: { focus?: boolean; minigame?: boolean; avatar?: boolean }) =>
  act(() => { applyWorldUiStateJson(JSON.stringify({ focus: false, minigame: false, avatar: false, ...patch })); });
const pressEnter = () => act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); });

describe('아바타 화면 중 HUD 숨김', () => {
  it('평상시에는 HUD와 채팅이 보인다', async () => {
    await renderWorld();
    expect(screen.getByTestId('world-hud')).not.toBeNull();
    expect(screen.getByTestId('world-chat')).not.toBeNull();
  });
  it('avatar:true 면 둘 다 사라진다', async () => {
    await renderWorld();
    setUi({ avatar: true });
    expect(screen.queryByTestId('world-hud')).toBeNull();
    expect(screen.queryByTestId('world-chat')).toBeNull();
  });
  it('focus 만으로는 숨기지 않는다', async () => {
    await renderWorld();
    setUi({ focus: true });
    expect(screen.getByTestId('world-hud')).not.toBeNull();
    expect(screen.getByTestId('world-chat')).not.toBeNull();
  });
  it('avatar:false 면 즉시 돌아온다', async () => {
    await renderWorld();
    setUi({ avatar: true });
    expect(screen.queryByTestId('world-hud')).toBeNull();
    setUi({ avatar: false });
    expect(screen.getByTestId('world-hud')).not.toBeNull();
    expect(screen.getByTestId('world-chat')).not.toBeNull();
  });
  it('resetWorldUiState에도 돌아온다', async () => {
    await renderWorld();
    setUi({ avatar: true });
    expect(screen.queryByTestId('world-hud')).toBeNull();
    act(() => { resetWorldUiState(); });
    expect(screen.getByTestId('world-hud')).not.toBeNull();
    expect(screen.getByTestId('world-chat')).not.toBeNull();
  });
  it('아바타 중 Enter 는 채팅을 열지 않는다', async () => {
    setMemberSession('at', '2026-12-31T00:00:00.000Z');
    await renderWorld();
    expect(getWorldChatSnapshot().open).toBe(false);
    setUi({ avatar: true });
    pressEnter();
    expect(getWorldChatSnapshot().open).toBe(false);
    setUi({ avatar: false });
    pressEnter();
    expect(getWorldChatSnapshot().open).toBe(true);
  });
});
