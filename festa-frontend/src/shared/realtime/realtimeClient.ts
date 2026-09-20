// 실시간 공용 통로 — 한 WebSocket 위에 여러 소비자가 올라탄다 (S15P21A604-706, GitLab #187).
//
// 서버가 `/ws` 하나로 상담 알림과 월드 채팅을 함께 나른다. 그래서 채팅 전용 소켓을 따로 열지
// 않고 여기 하나를 두고 소비자가 구독을 얹는다. 상담 real 어댑터가 도착하면 같은 자리에 붙는다.
//
// **허용 외 destination 은 소켓에 내보내지 않는다.** 서버는 목록 밖 SEND·SUBSCRIBE 에 ERROR 를
// 주고 세션을 닫는다 — 한 소켓이라 채팅 오타 하나가 상담 알림까지 죽인다. 그래서 나가기 전에
// 여기서 막고 개발자 오류로 던진다.
import { api, isApiError } from '../api/client';
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

/**
 * 마지막으로 소켓이 닫힌 사실 (S15P21A604-725). 실배포에서 "연결이 끊겼습니다" 토스트만 뜨고
 * 콘솔에 아무것도 없어 어디서 끊겼는지 특정할 수 없었다 — close code·reason, 서버 ERROR 프레임,
 * 재연결 중단 사유를 전부 콘솔에 남기고, 마지막 것은 여기 둔다. 실패를 조용히 삼키지 않는다 (T-24).
 */
export interface RealtimeCloseInfo {
  code: number;
  reason: string;
  wasClean: boolean;
  at: string;
  /** 닫히기 직전 서버가 보낸 ERROR 프레임 message. 없으면 null */
  serverError: string | null;
}
let lastClose: RealtimeCloseInfo | null = null;

/**
 * 지금 연결이 어떤 상태인가 (S15P21A604-790). `lastClose` 는 이미 지나간 사실이라 화면이 현재를
 * 그릴 수 없었다 — 채팅은 "지금 보낼 수 있는가" 를 물어야 해서 반응형 값이 따로 필요하다.
 *
 * `reconnecting` 은 **연결을 시도하는 중** 전부다. 최초 연결과 재연결을 가르지 않는 이유는
 * 화면이 둘을 다르게 대할 이유가 없어서다 — 어느 쪽이든 아직 못 보낸다.
 */
export type RealtimeStatus = 'connected' | 'reconnecting' | 'disconnected';
let status: RealtimeStatus = 'disconnected';
const statusListeners = new Set<() => void>();

function setStatus(next: RealtimeStatus): void {
  if (status === next) return;
  status = next;
  for (const listener of statusListeners) listener();
}

/** 화면이 읽는 현재 연결 상태 */
export function getRealtimeStatus(): RealtimeStatus {
  return status;
}

export function subscribeRealtimeStatus(listener: () => void): () => void {
  statusListeners.add(listener);
  return () => {
    statusListeners.delete(listener);
  };
}

const subscriptions = new Map<string, Subscription>();
let socket: WebSocket | null = null;
let connected = false;
let stopped = true;
let attempts = 0;
/**
 * 진행 중인 연결 시도 — 병렬 호출이 같은 promise 를 공유한다. 가드(`socket !== null`)와
 * 할당(`socket = next`) 사이에 `await fetchToken()` 이 있어, 마운트 시점에 나란히 호출되면
 * 둘 다 가드를 통과해 소켓이 두 개 열린다. 소켓이 두 개면 서버가 두 세션으로 보고 broadcast
 * 마다 MESSAGE 를 두 번 보내 채팅이 두 줄씩 보인다(2026-09-18 실측).
 */
let connectTask: Promise<void> | null = null;
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
    setStatus('connected');
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
    // 헤더 전체를 남긴다 — 서버는 ERROR 프레임에 원인을 message 와 헤더로 나눠 싣는다 (-706)
    console.error('[realtime] 서버가 ERROR 프레임을 보냈다 —', message, frame.headers);
    if (message === lastErrorMessage) {
      // 같은 원인이 반복된다. 조용히 되풀이하면 상담 알림이 죽은 줄 모른다.
      stopped = true;
      showToast('실시간 연결을 유지하지 못했습니다. 새로고침해 주세요', 'error');
    }
    lastErrorMessage = message;
  }
}

function scheduleReconnect(why: string): void {
  if (stopped || subscriptions.size === 0) {
    setStatus('disconnected');
    if (stopped) console.warn('[realtime] 재연결하지 않는다 — 이미 멈춘 상태:', why);
    return;
  }
  attempts += 1;
  if (attempts > MAX_ATTEMPTS) {
    stopped = true;
    setStatus('disconnected');
    console.error(`[realtime] 재연결 중단 — ${MAX_ATTEMPTS}회 연속 실패. 마지막 원인: ${why}`, lastClose);
    showToast('실시간 연결이 끊겼습니다. 새로고침해 주세요', 'error');
    return;
  }
  const delay = Math.min(1000 * 2 ** (attempts - 1), 15_000);
  setStatus('reconnecting');
  console.warn(`[realtime] 재연결 ${attempts}/${MAX_ATTEMPTS} — ${delay}ms 뒤 (${why})`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    void connectRealtime();
  }, delay);
}

/** 토큰을 새로 받아 소켓을 연다. 재연결마다 새 토큰이다 — 수명이 5분이라 재사용할 수 없다 */
export async function connectRealtime(): Promise<void> {
  if (socket !== null) return;
  // 병렬 호출은 진행 중인 promise 를 공유한다 — 위 가드와 아래 socket 할당 사이에
  // await fetchToken() 이 있어 둘 다 통과하면 소켓이 두 개 열린다. 소켓이 두 개면 서버가
  // 두 세션으로 보고 broadcast 마다 MESSAGE 를 두 번 보내 채팅이 두 줄씩 보인다(2026-09-18).
  if (connectTask !== null) return connectTask;
  connectTask = doConnect().finally(() => {
    connectTask = null;
  });
  return connectTask;
}

async function doConnect(): Promise<void> {
  if (socket !== null) return;
  stopped = false;
  setStatus('reconnecting');

  let token: string;
  try {
    ({ token } = await fetchToken());
  } catch (error) {
    // ws-token 발급 실패는 소켓이 열리기 전의 끊김이다. 던지고 끝내면 재연결 timer 가 호출한 경우
    // unhandled rejection 하나로 사라져 콘솔에도 화면에도 남지 않았다 — 원인을 적고 재연결 절차를 탄다
    const detail = isApiError(error)
      ? `${error.code}${error.status ? ` (${error.status})` : ''}: ${error.message}`
      : error instanceof Error
        ? error.message
        : String(error);
    console.error('[realtime] ws-token 발급 실패 —', detail);
    scheduleReconnect(`ws-token 발급 실패: ${detail}`);
    return;
  }
  const next = openSocket(realtimeSocketUrl());
  socket = next;

  next.onopen = () => {
    // 토큰은 CONNECT 헤더로만 넘긴다. URL query 에 실으면 접근 로그에 남는다 (헌법 13조).
    //
    // heart-beat 의 두 번째 값이 0 이 아닌 이유 (S15P21A604-819, GitLab #210): 이 소켓 위로
    // 흐르는 것은 구독 둘뿐이고, 아무도 채팅을 치지 않으면 바이트가 0 이다. 그 유휴 소켓을
    // 중간 계층이 Close handshake 없이 끊어 code=1006 reason="" 이 반복됐다 — 우리 nginx 는
    // proxy_read_timeout 180s 이고 앞단 Cloudflare 도 유휴 연결을 자체적으로 정리한다.
    //
    // "0,20000" 은 "나는 보내지 않고, 서버는 20초 안쪽으로 보내 달라" 다. 협상값이
    // max(서버 송신값, 클라 수신 희망값) 이라 **BE 의 setHeartbeatValue 와 짝이어야 효과가 난다** —
    // 서버가 0 이면 아무것도 오지 않고 동작은 종전과 같다(무해).
    //
    // 타이머도 watchdog 도 만들지 않는다. 서버가 보내는 개행 한 줄은 decodeFrame 이 null 로
    // 흘려보내고 handleFrame 이 첫 줄에서 반환한다 — 이미 통과하는 경로다.
    write({
      command: 'CONNECT',
      headers: { 'accept-version': '1.2', 'heart-beat': '0,20000', Authorization: 'Bearer ' + token },
      body: '',
    });
  };
  next.onmessage = (event: MessageEvent) => handleFrame(decodeFrame(String(event.data)));
  next.onerror = (event: Event) => {
    // 브라우저는 error 이벤트에 원인을 싣지 않는다 — 곧 오는 close 의 code 가 원인이다. 그래도 순서를 남긴다
    console.error('[realtime] 소켓 오류 (close 가 뒤따른다)', event.type);
  };
  next.onclose = (event: CloseEvent) => {
    connected = false;
    socket = null;
    lastClose = {
      code: event.code,
      reason: event.reason,
      wasClean: event.wasClean,
      at: new Date().toISOString(),
      serverError: lastErrorMessage,
    };
    console.warn(
      `[realtime] 소켓이 닫혔다 — code=${event.code} reason=${JSON.stringify(event.reason)} clean=${event.wasClean}` +
        (lastErrorMessage ? ` 서버 ERROR=${JSON.stringify(lastErrorMessage)}` : ''),
    );
    scheduleReconnect(`close code=${event.code}${event.reason ? ` reason=${event.reason}` : ''}`);
  };
}

/** 마지막 끊김의 사실 — 화면·진단이 읽는다. 아직 끊긴 적 없으면 null */
export function getRealtimeLastClose(): RealtimeCloseInfo | null {
  return lastClose;
}

export function subscribeRealtime(destination: string, handler: (body: string) => void): () => void {
  assertAllowed(ALLOWED_SUBSCRIBE_DESTINATIONS, destination, 'SUBSCRIBE');

  const sub: Subscription = { id: 'sub-' + String(nextId++), destination, handler };
  subscriptions.set(sub.id, sub);
  if (connected) sendSubscribe(sub);

  return () => {
    subscriptions.delete(sub.id);
    if (connected) write({ command: 'UNSUBSCRIBE', headers: { id: sub.id }, body: '' });
    // A member logout can remove the last subscriber while the WebSocket still carries the former
    // member's authenticated Principal. Keep shared consumers alive, but close immediately when
    // none remain so that identity cannot outlive the client session.
    if (subscriptions.size === 0) disconnectRealtime();
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
  setStatus('disconnected');
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
  connectTask = null;
  connected = false;
  stopped = true;
  attempts = 0;
  lastErrorMessage = null;
  lastClose = null;
  nextId = 1;
  status = 'disconnected';
}
