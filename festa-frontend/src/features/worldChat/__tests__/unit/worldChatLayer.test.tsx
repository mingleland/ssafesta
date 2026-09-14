// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import { CHAT_ERROR_MESSAGE, __resetWorldChatForTests, getWorldChatSnapshot } from '../../model/worldChat';
import { WorldChatLayer } from '../../ui/WorldChatLayer';

const FUTURE = '2026-12-31T00:00:00.000Z';

beforeEach(() => {
  __resetWorldChatForTests();
  __resetSessionForTests();
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
