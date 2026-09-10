// @vitest-environment jsdom
// 캐릭터 선택·로딩 중에는 World HUD 를 띄우지 않는다 (S15P21A604-613).
//
// Unity 가 로비를 그리는 동안 "W A S D 이동"·"부스 입장" 안내가 떠 있으면 사실과 다르고,
// 상담·도움말도 그 맥락에서 열 수 있는 것이 아니다. 사용자가 캐릭터 선택 화면 스크린샷으로
// 지적한 것이 정확히 그 상태다.
//
// 여기서 고정하는 것은 **단계별 노출**이다 — 월드 안(`ready`)에서만 보인다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

// Unity 인스턴스를 띄우지 않는다 — 이 테스트가 보는 것은 단계에 따른 HUD 노출뿐이다
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: false,
  WorldSurface: () => <div data-testid="world-surface" />,
}));

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => Promise.resolve(null), getSlots: () => Promise.resolve([]) },
}));

const { WorldPage } = await import('../../WorldPage');
const { setHostPhase, __resetHostPhaseForTests } = await import('../../../../unity/host/hostPhase');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import(
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

/** 사용자가 지적한 3종 — 조작 안내 · 상담(알림) · 도움말 */
function hudVisible(): boolean {
  return screen.queryByLabelText('조작 안내') !== null;
}

beforeEach(() => {
  __resetHostPhaseForTests();
  __resetSessionForTests();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});

afterEach(() => {
  cleanup();
  __resetHostPhaseForTests();
  __resetSessionForTests();
});

describe('World HUD 는 월드 안에서만 뜬다', () => {
  it('부팅 중에는 뜨지 않는다', () => {
    renderWorld();
    expect(hudVisible()).toBe(false);
  });

  it('캐릭터 선택 중(waiting-gate)에는 뜨지 않는다 — 사용자가 지적한 그 화면', () => {
    renderWorld();
    act(() => setHostPhase('waiting-gate'));

    expect(hudVisible()).toBe(false);
    // 상담(알림)·도움말도 함께 사라져야 한다
    expect(screen.queryByLabelText('상담')).toBeNull();
  });

  it('월드 로딩 중(preparing-world)에도 뜨지 않는다', () => {
    renderWorld();
    act(() => setHostPhase('preparing-world'));
    expect(hudVisible()).toBe(false);
  });

  it('boot 실패에도 뜨지 않는다', () => {
    renderWorld();
    act(() => setHostPhase('failed'));
    expect(hudVisible()).toBe(false);
  });

  it('월드에 들어가면(ready) 뜬다', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    expect(hudVisible()).toBe(true);
  });

  it('월드에 들어갔다가 다시 로비로 돌아가면 사라진다', () => {
    renderWorld();
    act(() => setHostPhase('ready'));
    expect(hudVisible()).toBe(true);

    act(() => setHostPhase('waiting-gate'));
    expect(hudVisible()).toBe(false);
  });
});
