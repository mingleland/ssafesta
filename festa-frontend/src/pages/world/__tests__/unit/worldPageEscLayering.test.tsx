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
  openManagementPanel,
} from '../../../../features/world/model/gameClientUi';
import { getWorldScreen } from '../../../../features/world/model/worldScreen';
import { __resetSessionForTests, setMemberSession } from '../../../../features/auth/model/session';
import {
  WORLD_CHAT_INPUT_ID,
  __resetWorldChatForTests,
  getWorldChatSnapshot,
} from '../../../../features/worldChat/model/worldChat';
import {
  __resetWorldUiStateForTests,
  applyWorldUiStateJson,
} from '../../../../unity/bridge/worldUiState';

// Unity 인스턴스·명령은 배선만 본다. 실제 SendMessage 는 이 테스트의 관심사가 아니다.
const fakeInstance = { SendMessage: vi.fn() };
const getReadyUnityInstance = vi.fn<() => typeof fakeInstance | null>(() => fakeInstance);
const requestExitWorldUi = vi.fn();
vi.mock('../../../../unity/host/sessionManager', () => ({
  getReadyUnityInstance: () => getReadyUnityInstance(),
}));
vi.mock('../../../../unity/host/worldUiBridge', () => ({
  requestExitWorldUi: (...args: unknown[]) => requestExitWorldUi(...args),
}));

// Unity·오버레이 내용물은 이 테스트의 관심사가 아니다 — ESC 배선만 본다.
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
// GameMenu 는 프로필·지갑 쿼리를 끌고 온다 — ESC 배선 테스트에 QueryClientProvider 를 세우지 않는다.
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => <div data-testid="game-menu" /> }));

const pressEscape = () =>
  act(() => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  });

const pressEnter = () =>
  act(() => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
  });

beforeEach(() => {
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
  getReadyUnityInstance.mockReturnValue(fakeInstance);
  requestExitWorldUi.mockClear();
});
afterEach(() => {
  cleanup();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});

/** Unity 가 모달을 쥐었다고 알려 온 상태를 만든다 — 실제 경로와 같은 수신부를 탄다. */
const unityModal = (patch: { focus?: boolean; minigame?: boolean; avatar?: boolean }) =>
  act(() => {
    applyWorldUiStateJson(JSON.stringify({ focus: false, minigame: false, avatar: false, ...patch }));
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
  it('월드일 때만 Toast 상단 중앙 예약 상태를 붙인다', async () => {
    await renderWorld();
    expect(document.body.classList.contains('world-active')).toBe(true);
  });

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

// Enter 판정도 같은 단일 중재자가 쥔다 (S15P21A604-706·-791) — 그래서 여기서 함께 잠근다.
describe('WorldPage Enter 판정 (-791)', () => {
  beforeEach(() => {
    __resetWorldChatForTests();
    __resetSessionForTests();
    setMemberSession('at', '2026-12-31T00:00:00.000Z');
  });
  afterEach(() => {
    __resetWorldChatForTests();
    __resetSessionForTests();
  });

  it('월드에서 누른 Enter 는 채팅을 열고 입력창에 focus 를 준다', async () => {
    await renderWorld();
    pressEnter();

    expect(getWorldChatSnapshot().open).toBe(true);
    expect(document.activeElement?.id).toBe(WORLD_CHAT_INPUT_ID);
  });

  it('패널이 열린 채 focus 를 잃어도 Enter 가 그 입력창으로 되돌린다 — 새로 열지 않는다', async () => {
    await renderWorld();
    pressEnter();

    // 캔버스를 클릭한 상태를 만든다 — 패널은 그대로 떠 있고 focus 만 빠진다
    act(() => {
      (document.activeElement as HTMLElement | null)?.blur();
    });
    expect(document.activeElement?.id).not.toBe(WORLD_CHAT_INPUT_ID);

    pressEnter();
    expect(getWorldChatSnapshot().open).toBe(true);
    expect(document.activeElement?.id).toBe(WORLD_CHAT_INPUT_ID);
  });
});

// Unity 가 쥔 모달까지 함께 중재한다 (-450 2차, GitLab #132).
// 전에는 이 판정의 입력값이 FE store 둘뿐이라, 줌만 켜진 상태의 ESC 가 "떠 있는 게 없다" 로 읽혀
// 줌은 풀리는데 Game Menu 가 같이 떴다.
describe('WorldPage ESC 중재 — Unity 모달 (-450, #132)', () => {
  it('FE 레이어가 없고 초점이 켜져 있으면 종료를 요청하고 Game Menu 를 열지 않는다', async () => {
    await renderWorld();
    unityModal({ focus: true });

    pressEscape();

    expect(requestExitWorldUi).toHaveBeenCalledTimes(1);
    expect(requestExitWorldUi).toHaveBeenCalledWith(fakeInstance, 'esc');
    expect(getWorldScreen()).toBe('world');
  });

  it('미니게임 HUD 도 같은 판정을 받는다 — 초점 전용 우회가 아니다', async () => {
    await renderWorld();
    unityModal({ minigame: true });

    pressEscape();

    expect(requestExitWorldUi).toHaveBeenCalledTimes(1);
    expect(getWorldScreen()).toBe('world');
  });

  // S15P21A604-820, GitLab #197 — 월드를 떠나지 않는 아바타 커스터마이징. 이 필드를 판정에
  // 넣지 않으면 ESC 가 아바타 화면 위에 Game Menu 를 연다.
  it('아바타 커스터마이징도 같은 판정을 받는다 — Game Menu 를 덧열지 않는다', async () => {
    await renderWorld();
    unityModal({ avatar: true });

    pressEscape();

    expect(requestExitWorldUi).toHaveBeenCalledTimes(1);
    expect(requestExitWorldUi).toHaveBeenCalledWith(fakeInstance, 'esc');
    expect(getWorldScreen()).toBe('world');
  });

  it('FE 레이어가 있으면 그것만 닫고 Unity 에는 아무것도 보내지 않는다 — 한 번에 하나다', async () => {
    await renderWorld();
    unityModal({ focus: true });
    act(() => { openOverlay('LAPTOP', { boothId: 1 }); });

    pressEscape();

    expect(getCurrentOverlay()).toBeNull();
    expect(requestExitWorldUi).not.toHaveBeenCalled();

    // 다음 ESC 가 Unity 차례다. 상태는 FE 가 임의로 지우지 않는다 — 정본은 Unity 다.
    pressEscape();
    expect(requestExitWorldUi).toHaveBeenCalledTimes(1);
  });

  it('Unity 모달이 없으면 예전대로 Game Menu 를 연다', async () => {
    await renderWorld();
    unityModal({ focus: false, minigame: false });

    pressEscape();

    expect(requestExitWorldUi).not.toHaveBeenCalled();
    expect(getWorldScreen()).toBe('menu');
  });

  it('인스턴스가 없으면 관측값을 비우고 Game Menu 로 내려간다 — ESC 가 아무 데도 가지 않게 두지 않는다', async () => {
    await renderWorld();
    unityModal({ focus: true });
    getReadyUnityInstance.mockReturnValue(null);

    pressEscape();

    expect(requestExitWorldUi).not.toHaveBeenCalled();
    expect(getWorldScreen()).toBe('menu');
  });

  // -755: 관리 상세가 관리 화면의 자식이 됐다. ESC 한 번이 두 겹을 함께 걷으면 상세를 닫았을 때
  // 관리 화면이 아니라 월드로 떨어진다.
  it('관리 상세가 떠 있으면 그것만 닫고 관리 화면으로 돌아온다', async () => {
    await renderWorld();
    act(() => { openManagementPanel({ kind: 'project', boothId: 42 }); });

    pressEscape();

    expect(getGameClientUiSnapshot().managementPanel).toBeNull();
    expect(getWorldScreen()).toBe('management');
  });

  // 네이티브 <dialog> 는 ESC 를 자기가 소비해 닫지만 keydown 은 window 까지 올라온다. 비켜 주지
  // 않으면 확인 모달이 닫히면서 그 아래 레이어까지 같이 사라진다.
  it('열린 dialog 가 있으면 ESC 가 상위 레이어를 닫지 않는다', async () => {
    await renderWorld();
    act(() => { openBoothManagement(); });

    const dialog = document.createElement('dialog');
    dialog.setAttribute('open', '');
    document.body.appendChild(dialog);

    pressEscape();

    expect(getWorldScreen()).toBe('management');
    dialog.remove();
  });
});
