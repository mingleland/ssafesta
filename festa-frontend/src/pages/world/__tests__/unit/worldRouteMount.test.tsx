// @vitest-environment jsdom
// 월드 화면을 떠나는 것과 Unity 를 내리는 것은 다른 일이다 (S15P21A604-620).
//
// `WorldPage` 는 부스 스튜디오·관리 상세로 나갈 때마다 언마운트된다. 그때 Unity 까지 죽으면
// 돌아올 때마다 50~84초를 다시 기다린다 — 그래서 이 화면은 **감추기만** 요청한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { __resetWorldMountForTests, getWorldMount } from '../../../../unity/host/worldMount';
import { __resetGameClientUiForTests } from '../../../../features/world/model/gameClientUi';
import { __resetWorldUiStateForTests } from '../../../../unity/bridge/worldUiState';

// 이 테스트의 관심사는 mount 배선 하나다 — 화면 내용물은 전부 비워 둔다.
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: true,
  WorldSurface: () => null,
}));
vi.mock('../../../../features/overlay/OverlayHost', () => ({ OverlayHost: () => null }));
vi.mock('../../../../features/world/ui/WorldHud', () => ({ WorldHud: () => null }));
vi.mock('../../../../features/world/ui/GameMenu', () => ({ GameMenu: () => null }));
vi.mock('../../../../features/booth/ui/BoothManagementOverlay', () => ({
  BoothManagementOverlay: () => null,
}));

const { WorldPage } = await import('../../WorldPage');

function renderWorld() {
  return render(
    <MemoryRouter>
      <WorldPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  __resetWorldMountForTests();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});

afterEach(() => {
  cleanup();
  __resetWorldMountForTests();
});

describe('월드 라우트와 Unity 수명', () => {
  it('들어가면 월드를 띄우고 보여 준다', () => {
    renderWorld();
    expect(getWorldMount()).toEqual({ mounted: true, visible: true });
  });

  it('나가면 감추기만 한다 — 내리지 않는다', () => {
    const { unmount } = renderWorld();
    unmount();

    // mounted 가 false 가 되면 다음 진입에서 Unity 가 처음부터 다시 뜬다
    expect(getWorldMount()).toEqual({ mounted: true, visible: false });
  });

  it('나갔다 돌아오면 다시 보인다 — 그 사이 내려간 적이 없다', () => {
    const first = renderWorld();
    first.unmount();
    expect(getWorldMount().mounted).toBe(true);

    renderWorld();
    expect(getWorldMount()).toEqual({ mounted: true, visible: true });
  });

  it('월드 화면 자체는 Unity 를 그리지 않는다 — 라우트 밖 상주 호스트가 그린다', () => {
    renderWorld();
    // .world-scene 은 남지만 그 안에 World Layer 는 없다
    expect(document.querySelector('.world-scene')).not.toBeNull();
    expect(document.querySelector('.persistent-world')).toBeNull();
  });
});
