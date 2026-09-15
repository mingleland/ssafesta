// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import { CHAT_ERROR_MESSAGE, __resetWorldChatForTests, getWorldChatSnapshot } from '../../model/worldChat';
import { WorldChatLayer } from '../../ui/WorldChatLayer';

// 연결 상태만 화면이 읽는 값으로 바꿔 끼운다. transport 의 나머지 동작은 실제 것을 그대로 쓴다
// — 소켓을 세우지 않고도 화면이 상태를 어떻게 그리는지만 본다 (S15P21A604-790).
const transport = vi.hoisted(() => ({ status: 'disconnected' as 'connected' | 'reconnecting' | 'disconnected' }));
vi.mock('../../../../shared/realtime/realtimeClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../../shared/realtime/realtimeClient')>()),
  getRealtimeStatus: () => transport.status,
  subscribeRealtimeStatus: () => () => {},
}));

const FUTURE = '2026-12-31T00:00:00.000Z';

beforeEach(() => {
  __resetWorldChatForTests();
  __resetSessionForTests();
  transport.status = 'connected';
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('WorldChatLayer', () => {
  it('게스트 버튼은 disabled 속성 없이 표현만 비활성이고, 눌러야 이유를 알려 준다', () => {
    setGuestSession('at', FUTURE);
    render(<WorldChatLayer />);

    const button = screen.getByRole('button', { name: '채팅' });
    // 실제 disabled 면 click 이 오지 않아 왜 못 쓰는지 말할 자리가 없다
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(button.getAttribute('aria-disabled')).toBe('true');

    fireEvent.click(button);
    expect(getWorldChatSnapshot().open).toBe(false);
    expect(screen.getByText(CHAT_ERROR_MESSAGE.MEMBER_ONLY)).toBeTruthy();
  });

  it('회원이 누르면 입력창이 열린다', () => {
    setMemberSession('at', FUTURE);
    render(<WorldChatLayer />);

    fireEvent.click(screen.getByRole('button', { name: '채팅' }));
    expect(getWorldChatSnapshot().open).toBe(true);
    expect(screen.getByLabelText('채팅 입력')).toBeTruthy();
  });

  it('채팅 레이어는 world-hud 밖이다 — HUD 의 mousedown 차단이 입력창 focus 를 막는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(container.querySelector('.world-chat')).not.toBeNull();
    expect(container.querySelector('.world-hud .world-chat')).toBeNull();
  });

  it('실제 높이 하나를 형제 HUD 레이아웃에 전달한다', () => {
    class ResizeObserverMock {
      private readonly callback: ResizeObserverCallback;

      constructor(callback: ResizeObserverCallback) {
        this.callback = callback;
      }
      observe(target: Element) {
        this.callback([{ target, contentRect: { height: 112 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      }
      disconnect() {}
      unobserve() {}
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverMock);
    const onHeightChange = vi.fn();

    render(<WorldChatLayer onHeightChange={onHeightChange} />);
    expect(onHeightChange).toHaveBeenLastCalledWith(112);
  });
});

describe('연결 상태 표시 (S15P21A604-790)', () => {
  function openAsMember() {
    setMemberSession('at', FUTURE);
    const view = render(<WorldChatLayer />);
    fireEvent.click(screen.getByRole('button', { name: '채팅' }));
    return view;
  }

  it('연결돼 있으면 아무 말도 하지 않는다 — 평상시에 군더더기를 두지 않는다', () => {
    const { container } = openAsMember();
    expect(screen.queryByRole('status')).toBeNull();
    expect(container.querySelector('.world-chat-input-row-offline')).toBeNull();
  });

  it('끊겼으면 그렇게 말하고 입력줄을 흐리게 둔다', () => {
    transport.status = 'disconnected';
    const { container } = openAsMember();
    expect(screen.getByRole('status').textContent).toBe('채팅 연결이 끊어졌습니다');
    expect(container.querySelector('.world-chat-input-row-offline')).not.toBeNull();
    // 입력창은 그대로 둔다 — 쓰던 값을 지키기 위해서다
    expect(screen.getByLabelText('채팅 입력')).toBeTruthy();
  });

  it('연결을 시도하는 중이면 끊겼다고 하지 않는다', () => {
    transport.status = 'reconnecting';
    openAsMember();
    expect(screen.getByRole('status').textContent).toBe('채팅 연결 중…');
  });

  it('게스트에게는 연결 상태를 말하지 않는다 — 연결을 시도조차 하지 않는다', () => {
    transport.status = 'disconnected';
    setGuestSession('at', FUTURE);
    render(<WorldChatLayer />);
    fireEvent.click(screen.getByRole('button', { name: '채팅' }));
    expect(screen.queryByRole('status')).toBeNull();
  });
});
