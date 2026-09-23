// @vitest-environment jsdom
// 이용 안내는 **월드에 들어설 때마다** 그 순간 뜬다 (S15P21A604-599).
//
// 전에는 자동 노출이 `WorldGuideLauncher` 에 있었는데 그 컴포넌트를 아무도 렌더하지 않아
// (HUD 정리 2026-09-16 에서 빠졌다) 첫 진입 환영 화면이 영영 뜨지 않았다. 판정을 "들어왔다" 를
// 아는 유일한 자리(WorldPage)로 옮겼으므로, 여기서 고정하는 것은 **뜨는 시점**이다.
//
// `ready` 는 Unity 가 엘리베이터 문을 열기 시작할 때 오고 실제 입장은 문이 다 열린 뒤다
// (`WorldEntryGate`, DoorSlideSeconds=1.1s). 그 사이에 뜨면 엘리베이터 칸 위에 얹히므로
// **문이 열리는 동안은 뜨지 않는 것**까지 함께 고정한다.
//
// 회원·게스트를 가리지 않고, 본 적 있는지도 묻지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: false,
  WorldSurface: () => <div data-testid="world-surface" />,
}));

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => Promise.resolve(null), getSlots: () => Promise.resolve([]) },
}));

const { WorldPage } = await import('../../WorldPage');
const { setHostPhase, __resetHostPhaseForTests } = await import('../../../../unity/host/hostPhase');
const { getCurrentOverlay, closeOverlay } = await import('../../../../shared/types/overlay');
const { applyWorldUiStateJson, __resetWorldUiStateForTests } = await import(
  '../../../../unity/bridge/worldUiState'
);
const { __resetSessionForTests, markBootstrapped, setGuestSession, setMemberSession } = await import(
  '../../../../features/auth/model/session'
);

function renderWorld() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/app/world']}>
        <WorldPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const guideOpen = () => getCurrentOverlay()?.type === 'WORLD_GUIDE';

/** 엘리베이터 문이 다 열릴 때까지 — WorldPage 의 GATE_DOOR_SLIDE_MS 와 같은 값이다. */
const DOOR_SLIDE_MS = 1100;
const openDoors = () => act(() => { vi.advanceTimersByTime(DOOR_SLIDE_MS); });

// 기존 avatar HUD 테스트와 같은 길로 넣는다 — Unity 가 보내는 JSON 이 정본이다.
const setUi = (patch: { focus?: boolean; minigame?: boolean; avatar?: boolean }) =>
  act(() => {
    applyWorldUiStateJson(JSON.stringify({ focus: false, minigame: false, avatar: false, ...patch }));
  });

beforeEach(() => {
  vi.useFakeTimers();
  __resetHostPhaseForTests();
  __resetSessionForTests();
  __resetWorldUiStateForTests();
  closeOverlay();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  closeOverlay();
  __resetWorldUiStateForTests();
  __resetHostPhaseForTests();
  __resetSessionForTests();
});

describe('이용 안내 자동 노출', () => {
  it('부팅·캐릭터 선택 중에는 뜨지 않는다 — 들어오기 전에 미리 뜨면 혼자 사라진다', () => {
    renderWorld();
    openDoors();
    expect(guideOpen()).toBe(false);

    act(() => setHostPhase('waiting-gate'));
    openDoors();
    expect(guideOpen()).toBe(false);
  });

  it('엘리베이터 문이 열리는 동안에는 뜨지 않는다 — 칸 위에 얹히지 않게', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    act(() => { vi.advanceTimersByTime(DOOR_SLIDE_MS - 1); });

    expect(guideOpen()).toBe(false);
  });

  it('문이 다 열려 월드가 드러나는 순간 뜬다', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    openDoors();

    expect(guideOpen()).toBe(true);
  });

  it('게스트도 똑같이 뜬다 — 처음 오는 쪽은 오히려 게스트다', () => {
    __resetSessionForTests();
    setGuestSession('token', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();

    renderWorld();
    act(() => setHostPhase('ready'));
    openDoors();

    expect(guideOpen()).toBe(true);
  });

  it('닫고 다시 들어와도 또 뜬다 — 본 적 있는지를 묻지 않는다', () => {
    const first = renderWorld();
    act(() => setHostPhase('ready'));
    openDoors();
    expect(guideOpen()).toBe(true);

    act(() => closeOverlay());
    first.unmount();
    __resetHostPhaseForTests();

    renderWorld();
    act(() => setHostPhase('ready'));
    openDoors();
    expect(guideOpen()).toBe(true);
  });

  // 관리 화면의 "부스 임대하기" 가 /app/booths → /app/world?panel=rental 로 돌아올 때가 이 경우다.
  // 월드는 이미 ready 이고 WorldPage 만 다시 선다 — 안내가 뜨면 방금 연 임대 창을 닫아 버린다.
  it('이미 월드 안에서 라우트만 돌아오면 입장이 아니다 — 뜨지 않는다', () => {
    act(() => setHostPhase('ready'));
    renderWorld();
    openDoors();

    expect(guideOpen()).toBe(false);
  });

  it('아바타 변경에서 돌아오는 것은 입장이 아니다 — 다시 뜨지 않는다', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    openDoors();
    act(() => closeOverlay());

    setUi({ avatar: true });
    setUi({ avatar: false });
    openDoors();

    expect(guideOpen()).toBe(false);
  });

  it('들어서는 순간 아바타 화면이면 그 방문에는 뜨지 않는다', () => {
    renderWorld();
    setUi({ avatar: true });
    act(() => setHostPhase('ready'));
    openDoors();

    expect(guideOpen()).toBe(false);
  });

  it('문이 열리는 중에 아바타 화면이 올라오면 그 위에 얹지 않는다', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    setUi({ avatar: true });
    openDoors();

    expect(guideOpen()).toBe(false);
  });
});
