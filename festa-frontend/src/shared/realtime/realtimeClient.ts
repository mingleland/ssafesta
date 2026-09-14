// 실시간 공용 통로 — 한 WebSocket 위에 여러 소비자가 올라탄다 (S15P21A604-706, GitLab #187).
//
// 서버가 `/ws` 하나로 상담 알림과 월드 채팅을 함께 나른다. 그래서 채팅 전용 소켓을 따로 열지
// 않고 여기 하나를 두고 소비자가 구독을 얹는다. 상담 real 어댑터가 도착하면 같은 자리에 붙는다.
//
// **허용 외 destination 은 소켓에 내보내지 않는다.** 서버는 목록 밖 SEND·SUBSCRIBE 에 ERROR 를
// 주고 세션을 닫는다 — 한 소켓이라 채팅 오타 하나가 상담 알림까지 죽인다. 그래서 나가기 전에
// 여기서 막고 개발자 오류로 던진다.
import { api } from '../api/client';
import { apiBaseUrl } from '../config/runtime';
import { showToast } from '../ui/toast/toastStore';
import { ALLOWED_SEND_DESTINATIONS, ALLOWED_SUBSCRIBE_DESTINATIONS } from './destinations';
import { decodeFrame, encodeFrame } from './stompFrame';

interface Subscription {
  id: string;
  destination: string;
  handler: (body: string) => void;
}

interface WsToken {
  token: string;
  expiresInSeconds: number;
}

const MAX_ATTEMPTS = 5;

const subscriptions = new Map<string, Subscription>();
let socket: WebSocket | null = null;
let connected = false;
let stopped = true;
let attempts = 0;
let lastErrorMessage: string | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let nextId = 1;

let openSocket: (url: string) => WebSocket = (url) => new WebSocket(url);
let fetchToken: () => Promise<WsToken> = () =>
  api<WsToken>('/api/v1/realtime/ws-token', { method: 'POST' });

/** 소켓 URL 은 API base 에서 파생한다 — 호스트를 하드코딩하지 않는다 (헌법 8조) */
export function realtimeSocketUrl(): string {
  return apiBaseUrl().replace(/^http/, 'ws') + '/ws';
}

function assertAllowed(list: readonly string[], destination: string, what: string): void {
  if (list.includes(destination)) return;
  // 던지고 끝낸다. 소켓으로 내보내면 서버가 세션을 닫아 상담 알림까지 함께 죽는다.
  throw new Error(`[realtime] 허용되지 않은 ${what} destination: ${destination}`);
}

function write(frame: Parameters<typeof encodeFrame>[0]): void {
  socket?.send(encodeFrame(frame));
}

function sendSubscribe(sub: Subscription): void {
  write({
    command: 'SUBSCRIBE',
    headers: { id: sub.id, destination: sub.destination, ack: 'auto' },
    body: '',
  });
}

function handleFrame(frame: ReturnType<typeof decodeFrame>): void {
  if (frame === null) return;

  if (frame.command === 'CONNECTED') {
    connected = true;
    attempts = 0;
    // 재연결이면 구독을 다시 건다 — 서버는 끊긴 사이의 이벤트를 재전송하지 않는다.
    for (const sub of subscriptions.values()) sendSubscribe(sub);
    return;
  }

  if (frame.command === 'MESSAGE') {
    const sub = subscriptions.get(frame.headers.subscription ?? '');
    sub?.handler(frame.body);
    return;
  }

  if (frame.command === 'ERROR') {
    const message = frame.headers.message ?? frame.body;
    console.error('[realtime] 서버가 연결을 끊었다 —', message);
    if (message === lastErrorMessage) {
      // 같은 원인이 반복된다. 조용히 되풀이하면 상담 알림이 죽은 줄 모른다.
      stopped = true;
      showToast('실시간 연결을 유지하지 못했습니다. 새로고침해 주세요', 'error');
    }
    lastErrorMessage = message;
  }
}

function scheduleReconnect(): void {
  if (stopped || subscriptions.size === 0) return;
  attempts += 1;
  if (attempts > MAX_ATTEMPTS) {
    stopped = true;
    showToast('실시간 연결이 끊겼습니다. 새로고침해 주세요', 'error');
    return;
  }
  const delay = Math.min(1000 * 2 ** (attempts - 1), 15_000);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    void connectRealtime();
  }, delay);
}

/** 토큰을 새로 받아 소켓을 연다. 재연결마다 새 토큰이다 — 수명이 5분이라 재사용할 수 없다 */
export async function connectRealtime(): Promise<void> {
  if (socket !== null) return;
  stopped = false;

  const { token } = await fetchToken();
  const next = openSocket(realtimeSocketUrl());
  socket = next;

  next.onopen = () => {
    // 토큰은 CONNECT 헤더로만 넘긴다. URL query 에 실으면 접근 로그에 남는다 (헌법 13조).
    write({
      command: 'CONNECT',
      headers: { 'accept-version': '1.2', 'heart-beat': '0,0', Authorization: 'Bearer ' + token },
      body: '',
    });
  };
  next.onmessage = (event: MessageEvent) => handleFrame(decodeFrame(String(event.data)));
  next.onclose = () => {
    connected = false;
    socket = null;
    scheduleReconnect();
  };
}

export function subscribeRealtime(destination: string, handler: (body: string) => void): () => void {
  assertAllowed(ALLOWED_SUBSCRIBE_DESTINATIONS, destination, 'SUBSCRIBE');

  const sub: Subscription = { id: 'sub-' + String(nextId++), destination, handler };
  subscriptions.set(sub.id, sub);
  if (connected) sendSubscribe(sub);

  return () => {
    subscriptions.delete(sub.id);
    if (connected) write({ command: 'UNSUBSCRIBE', headers: { id: sub.id }, body: '' });
  };
}

export function sendRealtime(destination: string, body: unknown): void {
  assertAllowed(ALLOWED_SEND_DESTINATIONS, destination, 'SEND');
  if (!connected) throw new Error('[realtime] 연결되지 않았다');
  write({
    command: 'SEND',
    headers: { destination, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export function isRealtimeConnected(): boolean {
  return connected;
}

export function disconnectRealtime(): void {
  stopped = true;
  if (reconnectTimer !== null) clearTimeout(reconnectTimer);
  reconnectTimer = null;
  subscriptions.clear();
  socket?.close();
  socket = null;
  connected = false;
}

// 테스트 전용
export function __configureRealtimeForTests(overrides: {
  openSocket?: (url: string) => WebSocket;
  fetchToken?: () => Promise<WsToken>;
}): void {
  if (overrides.openSocket) openSocket = overrides.openSocket;
  if (overrides.fetchToken) fetchToken = overrides.fetchToken;
}

export function __resetRealtimeForTests(): void {
  if (reconnectTimer !== null) clearTimeout(reconnectTimer);
  reconnectTimer = null;
  subscriptions.clear();
  socket = null;
  connected = false;
  stopped = true;
  attempts = 0;
  lastErrorMessage = null;
  nextId = 1;
}
