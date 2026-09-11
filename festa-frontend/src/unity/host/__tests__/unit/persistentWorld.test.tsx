// @vitest-environment jsdom
// 라우트보다 오래 사는 Unity 월드 (S15P21A604-620).
//
// 재는 것 하나: **월드 화면을 떠났을 때 Unity 가 죽는가.** 죽으면 돌아올 때마다 부팅 +
// 로비 + main 씬 로드로 50~84초를 다시 기다린다(#128). 그래서 여기서 잠그는 것은
// "감추기(hide)와 내리기(unmount)가 다른 일" 이라는 구분이다 — 합쳐지는 순간 회귀한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { PersistentWorld } from '../../PersistentWorld';
import {
  __resetWorldMountForTests,
  getWorldMount,
  hideWorld,
  showWorld,
  unmountWorld,
} from '../../worldMount';
import {
  __resetSessionForTests,
  clearSession,
  markBootstrapped,
  setGuestSession,
  setMemberSession,
} from '../../../../features/auth/model/session';

// 실제 WebGL 을 띄우지 않는다 — 재는 것은 canvas 가 DOM 에 남아 있는가이지 Unity 자체가 아니다.
vi.mock('../../../../features/world/ui/WorldSurface.select', () => ({
  IS_MOCK_WORLD: false,
  WorldSurface: () => <div data-testid="world-surface" />,
}));

const surface = () => screen.queryByTestId('world-surface');
const container = () => document.querySelector('.persistent-world');

function session(): void {
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
}

beforeEach(() => {
  __resetWorldMountForTests();
  __resetSessionForTests();
});

afterEach(() => {
  cleanup();
  __resetWorldMountForTests();
  __resetSessionForTests();
});

describe('상주 조건', () => {
  it('월드에 들어간 적이 없으면 아무것도 만들지 않는다 — 로그인 화면에서 WebGL 을 띄우지 않는다', () => {
    session();
    render(<PersistentWorld />);
    expect(surface()).toBeNull();
  });

  it('월드에 들어가면 뜬다', () => {
    session();
    render(<PersistentWorld />);
    act(() => showWorld());
    expect(surface()).not.toBeNull();
  });
});

describe('화면을 떠났을 때', () => {
  it('DOM 에서 떼지 않는다 — 이것이 재부팅 50~84초를 없애는 지점이다', () => {
    session();
    render(<PersistentWorld />);
    act(() => showWorld());
    act(() => hideWorld());

    expect(surface()).not.toBeNull();
    expect(getWorldMount().mounted).toBe(true);
  });

  it('보이지도 눌리지도 않는다 — 위에 뜬 화면의 클릭을 canvas 가 가로채지 않는다', () => {
    session();
    render(<PersistentWorld />);
    act(() => showWorld());
    act(() => hideWorld());

    const el = container();
    expect(el?.getAttribute('data-visible')).toBe('false');
    expect(el?.getAttribute('aria-hidden')).toBe('true');
  });

  it('다시 들어가면 그대로 보인다 — 새로 만들지 않는다', () => {
    session();
    render(<PersistentWorld />);
    act(() => showWorld());
    const first = surface();
    act(() => hideWorld());
    act(() => showWorld());

    expect(surface()).toBe(first); // 같은 노드다 — 재생성이면 다른 노드가 온다
    expect(container()?.getAttribute('data-visible')).toBe('true');
  });
});

describe('내리는 자리', () => {
  it('세션이 사라지면 내린다 — 돌아올 월드가 없다', () => {
    session();
    render(<PersistentWorld />);
    act(() => showWorld());

    act(() => clearSession());
    expect(surface()).toBeNull();
    expect(getWorldMount().mounted).toBe(false);
  });

  it('게스트는 내리지 않는다 — 월드는 guest-allowed 다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    render(<PersistentWorld />);
    act(() => showWorld());

    expect(surface()).not.toBeNull();
  });

  it('부트스트랩 전의 anonymous 로는 내리지 않는다 — 새로고침 복원이 지는 레이스를 만들지 않는다', () => {
    __resetSessionForTests(); // bootstrapped=false, kind=anonymous
    render(<PersistentWorld />);
    act(() => showWorld());

    expect(surface()).not.toBeNull();
  });
});

describe('worldMount 전이', () => {
  it('hide 는 감추기만 하고 unmount 는 내린다 — 두 일을 합치지 않는다', () => {
    showWorld();
    expect(getWorldMount()).toEqual({ mounted: true, visible: true });

    hideWorld();
    expect(getWorldMount()).toEqual({ mounted: true, visible: false });

    unmountWorld();
    expect(getWorldMount()).toEqual({ mounted: false, visible: false });
  });

  it('뜬 적 없는데 hide 하면 아무 일도 없다', () => {
    hideWorld();
    expect(getWorldMount()).toEqual({ mounted: false, visible: false });
  });
});
