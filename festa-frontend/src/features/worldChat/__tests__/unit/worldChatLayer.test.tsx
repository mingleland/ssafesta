// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  CHAT_ERROR_MESSAGE,
  __pushWorldChatErrorForTests,
  __pushWorldChatForTests,
  __resetWorldChatForTests,
  getWorldChatSnapshot,
  openWorldChat,
  sendWorldChat,
  setWorldChatDraft,
} from '../../model/worldChat';
import { WorldChatLayer } from '../../ui/WorldChatLayer';

// 연결 상태만 화면이 읽는 값으로 바꿔 끼운다. transport 의 나머지 동작은 실제 것을 그대로 쓴다
// — 소켓을 세우지 않고도 화면이 상태를 어떻게 그리는지만 본다 (S15P21A604-790).
const transport = vi.hoisted(() => ({
  status: 'disconnected' as 'connected' | 'reconnecting' | 'disconnected',
  // 전송은 소켓 없이 성공한 것으로 둔다 — 화면 테스트라 실제 연결을 세우지 않는다
  send: (() => {}) as (destination: string, body: unknown) => void,
}));
vi.mock('../../../../shared/realtime/realtimeClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../../shared/realtime/realtimeClient')>()),
  getRealtimeStatus: () => transport.status,
  subscribeRealtimeStatus: () => () => {},
  sendRealtime: (destination: string, body: unknown) => transport.send(destination, body),
}));

const FUTURE = '2026-12-31T00:00:00.000Z';

beforeEach(() => {
  __resetWorldChatForTests();
  __resetSessionForTests();
  transport.status = 'connected';
  transport.send = () => {};
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
    // 안내는 Passive 의 일시 안내 한 줄에 뜬다. 눈에 보이는 쪽과 읽히는 쪽이 갈려 있으므로
    // (초읽기 때문에) 읽히는 쪽을 본다.
    expect(screen.getByRole('alert').textContent).toBe(CHAT_ERROR_MESSAGE.MEMBER_ONLY);
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
    expect(container.querySelector('.world-chat-composer-offline')).toBeNull();
  });

  it('끊겼으면 그렇게 말하고 입력줄을 흐리게 둔다', () => {
    transport.status = 'disconnected';
    const { container } = openAsMember();
    expect(screen.getByRole('status').textContent).toBe('채팅 연결이 끊어졌습니다');
    expect(container.querySelector('.world-chat-composer-offline')).not.toBeNull();
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

  it('입장 이벤트는 로그에 섞이지 않는다 — 닫히면 일시 안내, 열면 상단 중앙 안내로 같은 한 줄이다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    act(() => __pushWorldChatForTests([
      { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T04:59:00.000Z', content: '먼저 한 말' },
      { type: 'JOIN', nickname: '황덕', sentAt: '2026-09-15T05:00:00.000Z' },
    ]));

    // 닫힌 상태 — 대화 4줄을 밀어내지 않도록 로그가 아니라 일시 안내 자리에 뜬다.
    // 읽기 알림(sr)은 여전히 마지막 **말**이 기준이다 (S15P21A604-855)
    expect(container.querySelector('.world-chat-log')?.textContent).not.toContain('입장하셨습니다');
    expect(container.querySelector('.world-chat-systemline .world-chat-flash')?.textContent)
      .toBe('황덕님이 입장하셨습니다.');
    expect(container.querySelector('.world-chat-sr')?.textContent).toBe('정헌: 먼저 한 말');

    act(() => openWorldChat());
    expect(container.querySelector('.world-chat-systemline')).toBeNull();
    // 열어도 로그에는 말만 남고, 사건은 상단 중앙 안내가 맡는다
    expect(container.querySelector('.world-chat-log')?.textContent).not.toContain('입장하셨습니다');
    expect(container.querySelector('.world-chat-notice-float .world-chat-flash')?.textContent)
      .toBe('황덕님이 입장하셨습니다.');
  });

  it('닫힌 상태에 입장 알림만 도착하면 대화 상자는 만들지 않는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    act(() => __pushWorldChatForTests([{ type: 'JOIN', nickname: '황덕', sentAt: '2026-09-15T05:00:00.000Z' }]));

    expect(container.querySelector('.world-chat-log')).toBeNull();
    expect(container.querySelector('.world-chat-sr')?.textContent).toBe('');
    expect(container.querySelector('.world-chat-flash')).not.toBeNull();
  });

  it('말이 오면 입장 안내가 그 자리를 내준다 — 지나간 입장이 위에 남지 않는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    act(() => __pushWorldChatForTests([{ type: 'JOIN', nickname: '황덕', sentAt: '2026-09-15T05:00:00.000Z' }]));
    expect(container.querySelector('.world-chat-flash')).not.toBeNull();

    act(() => __pushWorldChatForTests([
      { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:10.000Z', content: '왔어?' },
    ]));
    expect(container.querySelector('.world-chat-flash')).toBeNull();
  });
});

// UI 정본 — Passive 는 HUD, Active 는 Message/Status/Composer 세 층이다. 여기서 지키는 것은
// **레이아웃이 흔들리지 않는다**는 약속 하나다: 배지와 상태 문구가 나타났다 사라져도 입력칸이
// 제자리에 있어야 한다. 눈으로만 확인하면 다음 수정에서 조용히 깨진다.
describe('레이아웃 안정성 (UI 정본)', () => {
  const speaker = { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:00.000Z' };

  it('새 메시지 배지는 Message Area 안에 뜬다 — 형제로 두면 뜰 때마다 Composer 가 밀린다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    act(() => __pushWorldChatForTests([{ ...speaker, content: '먼저 와 있던 말' }]));

    const log = container.querySelector('.world-chat-log') as HTMLElement;
    Object.defineProperty(log, 'scrollHeight', { value: 400, configurable: true });
    Object.defineProperty(log, 'clientHeight', { value: 100, configurable: true });
    log.scrollTop = 0;
    fireEvent.scroll(log);
    act(() => __pushWorldChatForTests([{ ...speaker, content: '새 말' }]));

    const badge = screen.getByRole('button', { name: /새 메시지/ });
    expect(badge.closest('.world-chat-messages')).not.toBeNull();
  });

  it('상태 자리는 하나다 — 거절 사유가 오면 연결 상태 위에 쌓지 않고 대체한다', () => {
    transport.status = 'disconnected';
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    expect(screen.getByRole('status').textContent).toBe('채팅 연결이 끊어졌습니다');

    act(() => __pushWorldChatErrorForTests(JSON.stringify({ code: 'CHAT_UNAVAILABLE', message: '' })));

    const line = container.querySelector('.world-chat-notice-float') as HTMLElement;
    expect(line.children).toHaveLength(1);
    expect(screen.getByRole('alert').textContent).toBe(CHAT_ERROR_MESSAGE.CHAT_UNAVAILABLE);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('정상 Active 에는 상태 자리를 만들지 않는다 — 입력칸 위에 빈 띠가 남지 않는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));

    expect(container.querySelector('.world-chat-notice-float')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('Passive 에는 패널도 상태 자리도 없다 — Composer 는 감출 수 있게 제 클래스만 갖는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(container.querySelector('.world-chat-open')).toBeNull();
    expect(container.querySelector('.world-chat-notice-float')).toBeNull();
    expect(container.querySelector('.world-chat-composer')).not.toBeNull();
  });

  // jsdom 에는 레이아웃이 없다. 그래서 '몇 px 움직였나' 대신 **flow 에 무엇이 있는가** 를 본다 —
  // 패널의 직계 자식이 그대로면 그 아래 Composer 가 밀릴 방법이 없다.
  it('상태가 떴다 사라져도 패널의 flow 구성은 그대로다 — Composer 가 움직일 자리가 없다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    act(() => __pushWorldChatForTests([{ ...speaker, content: '한 줄' }]));

    const panel = container.querySelector('.world-chat') as HTMLElement;
    const flow = () => Array.from(panel.children).map((el) => el.className);
    const before = flow();
    expect(container.querySelector('.world-chat-notice-float')).toBeNull();

    act(() => __pushWorldChatErrorForTests(JSON.stringify({ code: 'CHAT_UNAVAILABLE', message: '' })));

    // 안내는 Message Viewport **안에서** 떠오른다 — 패널의 직계 자식은 하나도 늘지 않는다
    expect(container.querySelector('.world-chat-messages .world-chat-notice-float')).not.toBeNull();
    expect(flow()).toEqual(before);
  });

  it('새 메시지 배지와 시스템 안내는 위아래 끝으로 갈린다 — 겹치지 않고 자리도 안 뺏는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);
    fireEvent.focus(screen.getByLabelText('채팅 입력'));
    act(() => __pushWorldChatForTests([{ ...speaker, content: '먼저 와 있던 말' }]));

    const log = container.querySelector('.world-chat-log') as HTMLElement;
    Object.defineProperty(log, 'scrollHeight', { value: 400, configurable: true });
    Object.defineProperty(log, 'clientHeight', { value: 100, configurable: true });
    log.scrollTop = 0;
    fireEvent.scroll(log);
    act(() => __pushWorldChatForTests([{ ...speaker, content: '새 말' }]));

    const panel = container.querySelector('.world-chat') as HTMLElement;
    const before = Array.from(panel.children).map((el) => el.className);

    act(() => __pushWorldChatErrorForTests(JSON.stringify({ code: 'CHAT_UNAVAILABLE', message: '' })));

    // 안내는 Viewport 위쪽, 배지는 아래쪽 — 둘 다 같은 상자 안에서 뜨지만 닿을 자리가 없다
    const viewport = container.querySelector('.world-chat-messages') as HTMLElement;
    expect(viewport.querySelector('.world-chat-notice-float')).not.toBeNull();
    expect(viewport.querySelector('.world-chat-unread')).not.toBeNull();
    expect(Array.from(panel.children).map((el) => el.className)).toEqual(before);
  });
});

// Passive 도 사용자가 알아야 할 일은 알린다 — 단, 패널을 되살리지 않고 한 줄로.
// rate-limit 안내는 모델이 이미 들고 있는 cooldownUntil 을 읽어 남은 초를 센다.
describe('Passive 일시 안내 · rate-limit 초읽기 (UI 보정)', () => {
  it('Passive 에서도 연결 이상은 알린다 — 패널을 다시 만들지는 않는다', () => {
    transport.status = 'disconnected';
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(getWorldChatSnapshot().open).toBe(false);
    expect(container.querySelector('.world-chat-systemline')?.textContent).toBe('채팅 연결이 끊어졌습니다');
    expect(container.querySelector('.world-chat-open')).toBeNull();
    expect(container.querySelector('.world-chat-notice-float')).toBeNull();
  });

  it('정상 연결이면 Passive 에 아무 줄도 만들지 않는다', () => {
    setMemberSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(container.querySelector('.world-chat-systemline')).toBeNull();
  });

  it('게스트에게는 Passive 에서도 연결 상태를 말하지 않는다 — 연결을 시도조차 하지 않는다', () => {
    transport.status = 'disconnected';
    setGuestSession('at', FUTURE);
    const { container } = render(<WorldChatLayer />);

    expect(container.querySelector('.world-chat-systemline')).toBeNull();
  });

  it('rate-limit 안내가 남은 초를 센다 — 서버가 준 retryAfterMs 가 기준이고 draft 는 지킨다', () => {
    vi.useFakeTimers();
    try {
      setMemberSession('at', FUTURE);
      const { container } = render(<WorldChatLayer />);
      fireEvent.focus(screen.getByLabelText('채팅 입력'));
      act(() => setWorldChatDraft('안녕'));

      act(() => __pushWorldChatErrorForTests(
        JSON.stringify({ code: 'CHAT_TOO_FAST', message: '', retryAfterMs: 3000 }),
      ));
      const shown = () => container.querySelector('.world-chat-notice')?.textContent ?? '';
      expect(shown()).toContain('3초 후 다시 보낼 수 있어요');
      // 눈에 보이는 문구는 두 덩어리다 — 숫자는 따로 감싸 묻히지 않게 한다
      expect(container.querySelector('.world-chat-notice-head')?.textContent).toBe('조금 빠릅니다');
      expect(container.querySelector('.world-chat-notice-count')?.textContent).toBe('3초');

      act(() => { vi.advanceTimersByTime(1100); });
      expect(container.querySelector('.world-chat-notice-count')?.textContent).toBe('2초');

      // 읽히는 문구는 고정이다 — 숫자가 바뀔 때마다 alert 가 다시 울리면 3·2·1 을 연달아 읽는다
      expect(screen.getByRole('alert').textContent).toBe(CHAT_ERROR_MESSAGE.CHAT_TOO_FAST);

      // 초읽기는 쓰던 값을 건드리지 않고, 자리도 그대로다
      expect(getWorldChatSnapshot().draft).toBe('안녕');
      expect(container.querySelector('.world-chat-notice-float .world-chat-notice')).not.toBeNull();
      expect(container.querySelector('.world-chat-composer')).not.toBeNull();
   } finally {
     vi.useRealTimers();
   }
 });

  // 모델의 notice 는 6초를 살고 걷힌다. 벌칙이 그보다 길면 안내가 먼저 사라져 남은 대기를
  // 알 길이 없어진다 — 그래서 화면이 cooldown 이 끝나는 시각을 따로 붙잡는다.
  it.each([5, 10, 30])('%i초 벌칙은 notice TTL(6초)에 끊기지 않고 대기가 끝날 때까지 센다', (seconds) => {
    vi.useFakeTimers();
    try {
      setMemberSession('at', FUTURE);
      const { container } = render(<WorldChatLayer />);
      fireEvent.focus(screen.getByLabelText('채팅 입력'));
      act(() => setWorldChatDraft('안녕'));

      act(() => __pushWorldChatErrorForTests(
        JSON.stringify({ code: 'CHAT_TOO_FAST', message: '', retryAfterMs: seconds * 1000 }),
      ));
      const shown = () => container.querySelector('.world-chat-notice')?.textContent ?? '';
      expect(shown()).toContain(String(seconds) + '초 후 다시 보낼 수 있어요');

      // 대기가 1초 남은 시점 — 6초가 넘는 벌칙이면 모델의 notice 는 이미 걷혔는데도 보인다
      act(() => { vi.advanceTimersByTime((seconds - 1) * 1000); });
      if (seconds > 6) expect(getWorldChatSnapshot().notice).toBeNull();
      expect(shown()).toContain('1초 후 다시 보낼 수 있어요');

      // 대기가 끝나는 순간 스스로 걷힌다. 쓰던 값과 입력칸 자리는 그대로다.
      act(() => { vi.advanceTimersByTime(1000); });
      expect(container.querySelector('.world-chat-notice')).toBeNull();
      // 벌칙이 TTL 보다 짧으면 모델의 notice 는 아직 살아 있다 — 그래도 걷는다.
      // 대기가 끝난 사용자에게 "기다리라" 는 이미 틀린 말이다.
      if (seconds < 6) expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.CHAT_TOO_FAST);
      // 안내가 걷히면 자리도 함께 사라진다 — 빈 띠를 남기지 않는다
      expect(container.querySelector('.world-chat-notice-float')).toBeNull();
      expect(container.querySelector('.world-chat-composer')).not.toBeNull();
      expect(getWorldChatSnapshot().draft).toBe('안녕');
    } finally {
      vi.useRealTimers();
    }
  });

  it('정상 전송의 0.8초 cooldown 은 rate-limit 안내를 만들지 않는다 — 시각이 아니라 거절이 기준이다', () => {
    vi.useFakeTimers();
    try {
      const sent = vi.fn();
      transport.send = sent;
      setMemberSession('at', FUTURE);
      const { container } = render(<WorldChatLayer />);
      fireEvent.focus(screen.getByLabelText('채팅 입력'));

      act(() => { sendWorldChat('안녕'); });

      // 보내졌고 cooldown 도 걸렸지만, 거절당한 적이 없으므로 안내는 없다
      expect(sent).toHaveBeenCalledTimes(1);
      expect(getWorldChatSnapshot().cooldownUntil).toBeGreaterThan(Date.now());
      expect(container.querySelector('.world-chat-notice')).toBeNull();

      // 대기가 지나도 스스로 다시 보내지 않는다 — 보내는 것은 사용자다
      act(() => { vi.advanceTimersByTime(2_000); });
      expect(sent).toHaveBeenCalledTimes(1);
      expect(container.querySelector('.world-chat-notice')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

