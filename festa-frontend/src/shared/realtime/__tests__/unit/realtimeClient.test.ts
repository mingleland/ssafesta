import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeFrame } from '../../stompFrame';
import { WORLD_CHAT_ERRORS, WORLD_CHAT_TOPIC } from '../../destinations';
import {
  __configureRealtimeForTests,
  __resetRealtimeForTests,
  connectRealtime,
  realtimeSocketUrl,
  sendRealtime,
  subscribeRealtime,
} from '../../realtimeClient';

class FakeSocket {
  static last: FakeSocket | null = null;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  url: string;
  constructor(url: string) {
    this.url = url;
    FakeSocket.last = this;
  }
  send(raw: string) {
    this.sent.push(raw);
  }
  close() {
    this.onclose?.();
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
