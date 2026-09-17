// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  CHAT_ERROR_MESSAGE,
  __pushWorldChatForTests,
  __resetWorldChatForTests,
  getWorldChatSnapshot,
  setWorldChatDraft,
} from '../../model/worldChat';
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
  it('게스트 입력칸은 disabled 가 아니라 readOnly 다 — focus 가 와야 이유를 말할 수 있다', () => {
    setGuestSession('at', FUTURE);
    render(<WorldChatLayer />);

    const input = screen.getByLabelText('채팅 입력');
    // 실제 disabled 면 focus 가 오지 않아 왜 못 쓰는지 말할 자리가 없다
    expect(input.hasAttribute('disabled')).toBe(false);
    expect(input.hasAttribute('readonly')).toBe(true);

    fireEvent.focus(input);
    expect(getWorldChatSnapshot().open).toBe(false);
    expect(screen.getByText(CHAT_ERROR_MESSAGE.MEMBER_ONLY)).toBeTruthy();
  });

  it('입력창은 늘 떠 있고, 회원이 focus 하면 Active 로 들어간다', () => {
    setMemberSession('at', FUTURE);
    render(<WorldChatLayer />);

    expect(getWorldChatSnapshot().open).toBe(false);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
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
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
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
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    expect(screen.queryByRole('status')).toBeNull();
  });
});

// S15P21A604-791 — 읽던 줄을 새 메시지에 빼앗기지 않는다.
describe('로그 스크롤·새 메시지 (S15P21A604-791)', () => {
  const speaker = { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:00.000Z' };

  /** jsdom 은 레이아웃이 없어 스크롤 값이 전부 0 이다 — 읽는 위치를 직접 만들어 준다 */
  function fakeScroll(log: HTMLElement, { scrollTop, scrollHeight, clientHeight }: { scrollTop: number; scrollHeight: number; clientHeight: number }) {
    Object.defineProperty(log, 'scrollHeight', { value: scrollHeight, configurable: true });
    Object.defineProperty(log, 'clientHeight', { value: clientHeight, configurable: true });
    log.scrollTop = scrollTop;
  }

  /** 로그 상자는 받은 말이 하나 있어야 그려진다 — 한 줄 넣고 그 상자를 돌려준다 */
  function openWithLog() {
    setMemberSession('at', FUTURE);
    const view = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    act(() => __pushWorldChatForTests([{ ...speaker, content: '먼저 와 있던 말' }]));
    return view.container.querySelector('.world-chat-log') as HTMLElement;
  }

  it('맨 아래를 보고 있으면 새 메시지를 따라 내려간다', () => {
    const log = openWithLog();
    fakeScroll(log, { scrollTop: 0, scrollHeight: 400, clientHeight: 400 });

    act(() => __pushWorldChatForTests([{ ...speaker, content: '새 말' }]));

    expect(log.scrollTop).toBe(400);
    expect(screen.queryByRole('button', { name: /새 메시지/ })).toBeNull();
  });

  it('위쪽을 읽는 중이면 자리를 지키고 몇 개가 왔는지만 알린다', () => {
    const log = openWithLog();
    fakeScroll(log, { scrollTop: 0, scrollHeight: 400, clientHeight: 100 });
    fireEvent.scroll(log);

    act(() => __pushWorldChatForTests([{ ...speaker, content: '첫 줄' }]));
    act(() => __pushWorldChatForTests([{ ...speaker, content: '둘째 줄' }]));

    expect(log.scrollTop).toBe(0);
    const pill = screen.getByRole('button', { name: /새 메시지/ });
    expect(pill.textContent).toContain('2');

    fireEvent.click(pill);
    expect(log.scrollTop).toBe(400);
    expect(screen.queryByRole('button', { name: /새 메시지/ })).toBeNull();
  });
});

describe('접근성·표시 규칙 (S15P21A604-791)', () => {
  const speaker = { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:00.000Z' };

  it('새로 온 한 줄만 따로 읽힌다 — 로그 전체에는 aria-live 를 걸지 않는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    act(() =>
      __pushWorldChatForTests([
        { ...speaker, content: '먼저 온 말' },
        { ...speaker, content: '나중에 온 말' },
      ]),
    );

    const live = container.querySelector('[aria-live="polite"]');
    expect(live?.textContent).toBe('정헌: 나중에 온 말');
    expect(container.querySelector('.world-chat-log')?.getAttribute('aria-live')).toBeNull();
  });

  it('보내지 못한 이유는 즉시 알린다', () => {
    setGuestSession('at', FUTURE);
    render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toBe(CHAT_ERROR_MESSAGE.MEMBER_ONLY);
  });

  it('글자 수는 상한이 가까워질 때만 보인다', () => {
    setMemberSession('at', FUTURE);
    render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));

    act(() => setWorldChatDraft('가'.repeat(79)));
    expect(screen.queryByText('79/100')).toBeNull();

    act(() => setWorldChatDraft('가'.repeat(80)));
    expect(screen.getByText('80/100')).toBeTruthy();
  });

  it('받은 말이 없으면 빈 상자를 남기지 않는다 — 게스트가 보던 자리', () => {
    setGuestSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(container.querySelector('.world-chat-log')).toBeNull();
  });

  it('열었는데 아직 받은 말이 없으면 저장이 없다는 것만 한 줄로 알린다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));

    expect(container.querySelector('.world-chat-hint')?.textContent).toBe('월드 채팅은 접속 중인 동안만 표시됩니다');

    act(() => __pushWorldChatForTests([{ senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:00.000Z', content: '첫 말' }]));
    expect(container.querySelector('.world-chat-hint')).toBeNull();
  });

  it('입장 이벤트는 채팅 본문과 분리된 시스템 알림으로 표시한다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    act(() => __pushWorldChatForTests([{ type: 'JOIN', nickname: '황덕', sentAt: '2026-09-15T05:00:00.000Z' }]));

    expect(container.querySelector('.world-chat-join')?.textContent).toBe('황덕님이 입장하셨습니다.');
    expect(container.querySelector('.world-chat-sr')?.textContent).toBe('황덕님이 입장하셨습니다.');
  });
});

