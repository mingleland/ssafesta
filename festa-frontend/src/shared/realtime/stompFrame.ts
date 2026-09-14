// STOMP 프레임 한 겹 — 라이브러리를 들이지 않고 필요한 여섯 프레임만 직접 다룬다.
//
// 서버가 native WebSocket + STOMP 이고 SockJS 가 없다(spec 011 C-05). 우리가 쓰는 것은
// CONNECT / CONNECTED / SUBSCRIBE / SEND / MESSAGE / ERROR 뿐이라 여기까지가 필요한 전부다.

export interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

const NULL = '\u0000';

// STOMP 1.2 헤더 이스케이프. 토큰·destination 에는 거의 나오지 않지만, 한 번 새면 프레임 경계가
// 통째로 어긋나 원인을 찾기 어렵다.
function escapeHeader(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/:/g, '\\c');
}

function unescapeHeader(value: string): string {
  return value.replace(/\\(.)/g, (_match, ch: string) => {
    if (ch === 'r') return '\r';
    if (ch === 'n') return '\n';
    if (ch === 'c') return ':';
    return ch;
  });
}

export function encodeFrame(frame: StompFrame): string {
  const headers = Object.entries(frame.headers).map(([k, v]) => escapeHeader(k) + ':' + escapeHeader(v));
  return [frame.command, ...headers].join('\n') + '\n\n' + frame.body + NULL;
}

export function decodeFrame(raw: string): StompFrame | null {
  const text = raw.endsWith(NULL) ? raw.slice(0, -1) : raw;
  const split = text.indexOf('\n\n');
  if (split < 0) return null;

  const lines = text.slice(0, split).split('\n');
  const command = lines.shift();
  if (command === undefined || command === '') return null;

  const headers: Record<string, string> = {};
  for (const line of lines) {
    const at = line.indexOf(':');
    if (at < 0) continue;
    const key = unescapeHeader(line.slice(0, at));
    // STOMP 는 중복 헤더의 첫 값이 이긴다
    if (!(key in headers)) headers[key] = unescapeHeader(line.slice(at + 1));
  }

  return { command, headers, body: text.slice(split + 2) };
}
