import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeFrame } from '../../stompFrame';
import { WORLD_CHAT_ERRORS, WORLD_CHAT_TOPIC } from '../../destinations';
import {
  __configureRealtimeForTests,
  __resetRealtimeForTests,
  connectRealtime,
  getRealtimeLastClose,
  getRealtimeStatus,
  realtimeSocketUrl,
  sendRealtime,
  subscribeRealtime,
  subscribeRealtimeStatus,
} from '../../realtimeClient';

class FakeSocket {
  static last: FakeSocket | null = null;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number; reason: string; wasClean: boolean }) => void) | null = null;
  onerror: ((event: { type: string }) => void) | null = null;
  url: string;
  constructor(url: string) {
    this.url = url;
    FakeSocket.last = this;
  }
  send(raw: string) {
    this.sent.push(raw);
  }
  close(code = 1000, reason = '', wasClean = true) {
    this.onclose?.({ code, reason, wasClean });
  }
  /** 서버가 CONNECTED 를 돌려준 상태로 만든다 */
  accept() {
    this.onopen?.();
    this.onmessage?.({ data: 'CONNECTED\nversion:1.2\n\n\u0000' });
  }
  frames() {
    return this.sent.map((raw) => decodeFrame(raw));
  }
}

beforeEach(() => {
  __resetRealtimeForTests();
  FakeSocket.last = null;
  __configureRealtimeForTests({
    openSocket: (url) => new FakeSocket(url) as unknown as WebSocket,
    fetchToken: async () => ({ token: 'ws-token-1', expiresInSeconds: 300 }),
  });
});

afterEach(() => {
  __resetRealtimeForTests();
  vi.useRealTimers();
});

describe('realtimeClient', () => {
  it('토큰을 CONNECT 헤더로만 넘기고 URL 에는 싣지 않는다', async () => {
    await connectRealtime();
    const socket = FakeSocket.last!;
    socket.accept();

    expect(socket.url).not.toContain('ws-token-1');
    expect(realtimeSocketUrl().endsWith('/ws')).toBe(true);
    expect(realtimeSocketUrl()).not.toMatch(/^http/);
    const connect = socket.frames().find((f) => f?.command === 'CONNECT');
    expect(connect?.headers.Authorization).toBe('Bearer ws-token-1');
  });

  it('허용 목록 밖 destination 은 소켓에 나가지 않고 던진다 — 서버가 세션을 끊는다', async () => {
    await connectRealtime();
    const socket = FakeSocket.last!;
    socket.accept();
    const before = socket.sent.length;

    expect(() => subscribeRealtime('/topic/anything', () => {})).toThrow();
    expect(() => sendRealtime('/app/world/chatt', { content: 'x' })).toThrow();
    expect(socket.sent).toHaveLength(before);
  });

  it('소비자 둘이 소켓 하나를 나눠 쓰고 각자 자기 것만 받는다', async () => {
    const chat: string[] = [];
    const errors: string[] = [];
    subscribeRealtime(WORLD_CHAT_TOPIC, (body) => chat.push(body));
    subscribeRealtime(WORLD_CHAT_ERRORS, (body) => errors.push(body));

    await connectRealtime();
    const socket = FakeSocket.last!;
    socket.accept();

    const subs = socket.frames().filter((f) => f?.command === 'SUBSCRIBE');
    expect(subs).toHaveLength(2);
    const chatId = subs.find((f) => f?.headers.destination === WORLD_CHAT_TOPIC)!.headers.id;

    socket.onmessage?.({
      data: 'MESSAGE\nsubscription:' + chatId + '\n\n{"content":"hi"}\u0000',
    });

    expect(chat).toEqual(['{"content":"hi"}']);
    expect(errors).toEqual([]);
  });

  it('재연결하면 구독을 다시 건다 — 서버는 끊긴 사이의 이벤트를 재전송하지 않는다', async () => {
    vi.useFakeTimers();
    subscribeRealtime(WORLD_CHAT_TOPIC, () => {});

    await connectRealtime();
    FakeSocket.last!.accept();
    const first = FakeSocket.last!;
    first.close();

    await vi.advanceTimersByTimeAsync(1200);
    const second = FakeSocket.last!;
    expect(second).not.toBe(first);
    second.accept();

    expect(second.frames().filter((f) => f?.command === 'SUBSCRIBE')).toHaveLength(1);
  });
});

// S15P21A604-725 — 끊김의 원인이 콘솔과 진단값에 남는다. 토스트만 뜨고 아무것도 없던 자리.
describe('끊김 진단 (S15P21A604-725)', () => {
  it('소켓이 닫히면 close code·reason 과 직전 서버 ERROR 가 콘솔과 lastClose 에 남는다', async () => {
    vi.useFakeTimers();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    subscribeRealtime(WORLD_CHAT_TOPIC, () => {});
    await connectRealtime();
    FakeSocket.last!.accept();
    FakeSocket.last!.onmessage?.({ data: 'ERROR\nmessage:unauthorized\n\nbad token\u0000' });
    FakeSocket.last!.close(1008, 'policy violation', false);

    expect(error).toHaveBeenCalledWith('[realtime] 서버가 ERROR 프레임을 보냈다 —', 'unauthorized', expect.anything());
    expect(warn.mock.calls.map((c) => String(c[0])).join('\n')).toMatch(/소켓이 닫혔다 — code=1008 reason="policy violation" clean=false 서버 ERROR="unauthorized"/);
    expect(getRealtimeLastClose()).toMatchObject({ code: 1008, reason: 'policy violation', wasClean: false, serverError: 'unauthorized' });
    warn.mockRestore();
    error.mockRestore();
  });

  it('ws-token 발급 실패는 던지지 않고 원인을 적은 뒤 재연결 절차를 탄다', async () => {
    vi.useFakeTimers();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let calls = 0;
    __configureRealtimeForTests({
      fetchToken: async () => {
        calls += 1;
        if (calls === 1) throw { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', status: 401, errors: [], warnings: [] };
        return { token: 'ws-token-2', expiresInSeconds: 300 };
      },
    });
    subscribeRealtime(WORLD_CHAT_TOPIC, () => {});
    await expect(connectRealtime()).resolves.toBeUndefined();
    expect(error).toHaveBeenCalledWith('[realtime] ws-token 발급 실패 —', 'UNAUTHORIZED (401): 로그인이 필요합니다.');
    expect(warn.mock.calls.map((c) => String(c[0])).join('\n')).toMatch(/재연결 1\/5/);

    await vi.advanceTimersByTimeAsync(1200);
    expect(calls).toBe(2);
    expect(FakeSocket.last).not.toBeNull();
    error.mockRestore();
    warn.mockRestore();
  });

  it('재연결을 포기할 때 마지막 원인을 남긴다', async () => {
    vi.useFakeTimers();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    subscribeRealtime(WORLD_CHAT_TOPIC, () => {});
    await connectRealtime();
    for (let i = 0; i < 6; i += 1) {
      FakeSocket.last!.close(1006, '', false);
      await vi.advanceTimersByTimeAsync(20_000);
    }
    const giveUp = error.mock.calls.find((c) => String(c[0]).includes('재연결 중단'));
    expect(giveUp?.[0]).toMatch(/5회 연속 실패\. 마지막 원인: close code=1006/);
    expect(giveUp?.[1]).toMatchObject({ code: 1006 });
    vi.restoreAllMocks();
  });
});

// S15P21A604-790 — lastClose 는 지나간 사실이라 화면이 현재를 그릴 수 없었다.
describe('연결 상태 (S15P21A604-790)', () => {
  it('연결 전 disconnected · 시도 중 reconnecting · CONNECTED 를 받으면 connected 다', async () => {
    const seen: string[] = [];
    const stop = subscribeRealtimeStatus(() => seen.push(getRealtimeStatus()));

    expect(getRealtimeStatus()).toBe('disconnected');
    await connectRealtime();
    expect(getRealtimeStatus()).toBe('reconnecting');
    FakeSocket.last!.accept();
    expect(getRealtimeStatus()).toBe('connected');

    // 구독자는 바뀐 순간마다 한 번씩만 듣는다
    expect(seen).toEqual(['reconnecting', 'connected']);
    stop();
  });

  it('구독이 남아 있으면 닫힘은 reconnecting 이고, 재연결을 포기하면 disconnected 다', async () => {
    vi.useFakeTimers();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    subscribeRealtime(WORLD_CHAT_TOPIC, () => {});

    await connectRealtime();
    FakeSocket.last!.accept();
    expect(getRealtimeStatus()).toBe('connected');

    FakeSocket.last!.close(1006, '', false);
    expect(getRealtimeStatus()).toBe('reconnecting');

    for (let i = 0; i < 6; i += 1) {
      await vi.advanceTimersByTimeAsync(20_000);
      FakeSocket.last!.close(1006, '', false);
    }
    expect(getRealtimeStatus()).toBe('disconnected');
    vi.restoreAllMocks();
  });
});

// S15P21A604-819 — 유휴 소켓이 Close handshake 없이 끊기던 자리(GitLab #210).
describe('유휴 끊김 방지 heartbeat (S15P21A604-819)', () => {
  it('CONNECT 가 서버에 heartbeat 를 요청한다 — 0,20000', async () => {
    await connectRealtime();
    const socket = FakeSocket.last!;
    socket.accept();

    const connect = socket.frames().find((f) => f?.command === 'CONNECT');
    // 앞자리 0 은 그대로다 — 클라이언트는 보내지 않고 타이머도 만들지 않는다.
    expect(connect?.headers['heart-beat']).toBe('0,20000');
  });

  it('서버 heartbeat(개행 한 줄)는 상태를 바꾸지 않고, 그 뒤 MESSAGE 가 그대로 온다', async () => {
    const chat: string[] = [];
    subscribeRealtime(WORLD_CHAT_TOPIC, (body) => chat.push(body));
    await connectRealtime();
    const socket = FakeSocket.last!;
    socket.accept();
    const subId = socket.frames().find((f) => f?.command === 'SUBSCRIBE')!.headers.id;

    // STOMP heartbeat 는 개행 하나다. \r\n 으로 오는 서버도 있어 둘 다 본다.
    expect(() => socket.onmessage?.({ data: '\n' })).not.toThrow();
    expect(() => socket.onmessage?.({ data: '\r\n' })).not.toThrow();
    expect(getRealtimeStatus()).toBe('connected');
    expect(chat).toEqual([]);

    socket.onmessage?.({ data: 'MESSAGE\nsubscription:' + subId + '\n\n{"content":"hi"}\u0000' });
    expect(chat).toEqual(['{"content":"hi"}']);
  });
});
