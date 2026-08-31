// 개발용 목 서버 — 정적 파일 + world-session 발급 (S15P21A604-333)
//
// **왜 정적 파일만으로는 안 되나.** S15P21A604-85 로 게임 서버가 입장 토큰의 HS256 서명을
// 검증하고, `GrantReplayLedger` 가 `jti` 재사용을 거부한다. 그래서 world-session 응답은
// **매번 새로 서명된 토큰**이어야 하고 — 정적 JSON 파일로는 원리적으로 불가능하다.
// (한 번은 통해도 두 번째 접속에서 원장이 막는다.)
//
// **왜 클라이언트가 직접 서명하지 않나.** WebGL 에는 환경변수가 없어 시크릿을 줄 방법이
// `빌드에 박기` 뿐인데, 그러면 **입장 토큰 체계 전체가 무의미해진다**(누구나 grant 를 만든다).
// 실제 배포에서도 서명은 BE 가 한다. 이 서버는 그 자리를 개발 중에만 대신한다 —
// 시크릿은 서버 프로세스에만 있고 브라우저로 나가지 않는다.
//
// 실행:
//   CONNECTION_TOKEN_SECRET="<base64 32바이트 이상>" node serve.mjs --root ../../Builds/web --port 8000
//
// 게임 서버에도 **같은** 시크릿을 줘야 한다. 다르면 서명 검증에서 거부된다.

import { createServer } from 'node:http';
import { createHmac } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';
import { join, extname, resolve, sep } from 'node:path';

// ── 인자 ────────────────────────────────────────────────
const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const ROOT = resolve(argOf('--root', '.'));
const PORT = Number(argOf('--port', '8000'));

// 게임 서버가 기대하는 값. WorldEntryTokenVerifier 의 Expected* 상수와 한 쌍이다 —
// 한쪽만 바꾸면 "JWT 는 맞는데 거부" 라는 진단하기 어려운 실패가 된다.
const ISSUER = 'ssafesta-backend';
const AUDIENCE = 'ssafesta-world';
const WORLD_ID = '11F';
const CHANNEL_ID = '11F-01';
const TTL_SECONDS = 120;

const WORLD_HOST = argOf('--world-host', '127.0.0.1');
const WORLD_PORT = Number(argOf('--world-port', '7777'));
const WORLD_SCHEME = argOf('--world-scheme', 'ws');

// ── 시크릿 ──────────────────────────────────────────────
// 없으면 **뜨지 않는다.** 조용히 가짜 토큰을 주면 게임 서버 로그에
// "JWT 형식이 아니다" 만 남고 진짜 원인(키 없음)이 가려진다 — S15P21A604-331 에서 겪은 실패다.
const secretB64 = process.env.CONNECTION_TOKEN_SECRET;
if (!secretB64) {
  console.error(
    '[mock-api] CONNECTION_TOKEN_SECRET 이 비어 있어 시작하지 않는다.\n' +
    '           게임 서버와 같은 값을 주입해라. 예:\n' +
    '           CONNECTION_TOKEN_SECRET="$(head -c 32 /dev/urandom | base64 -w0)" node serve.mjs');
  process.exit(78);
}
const SECRET = Buffer.from(secretB64, 'base64');
if (SECRET.length < 32) {
  console.error(`[mock-api] 키가 ${SECRET.length}바이트다 — HS256 은 32바이트 이상이어야 한다.`);
  process.exit(78);
}

// ── 서명 ────────────────────────────────────────────────
const b64url = (buf) =>
  Buffer.from(buf).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');

let issued = 0;
function issueGrant() {
  const now = Math.floor(Date.now() / 1000);
  // jti 는 매번 달라야 한다 — 같은 값을 다시 쓰면 GrantReplayLedger 가 거부한다.
  const jti = `mockapi-${now}-${(issued++).toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  const playerId = '12';

  const header = { alg: 'HS256', typ: 'JWT' };
  const claims = {
    iss: ISSUER,
    // 실제 백엔드(Spring)는 aud 를 **단일 문자열**로 보낸다 (RFC 7519 §4.1.3 이 허용).
    // 여기서 배열로 보내면 이 목이 실물과 다른 형태를 검증기에 학습시킨다 — 검증기가
    // 배열만 받는 결함(S15P21A604-340)을 이 목이 정확히 그렇게 가렸다. 실물을 따라간다.
    aud: AUDIENCE,
    sub: playerId,
    jti,
    iat: now,
    exp: now + TTL_SECONDS,
    role: 'MEMBER',
    playerId,
    nickname: 'MockUser',
    sessionId: `mockapi-${jti}`,
    worldId: WORLD_ID,
    channelId: CHANNEL_ID,
    avatarCode: 'sk_01',
  };

  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const signature = createHmac('sha256', SECRET).update(signingInput, 'ascii').digest();
  return {
    token: `${signingInput}.${b64url(signature)}`,
    jti,
    expiresAt: new Date((now + TTL_SECONDS) * 1000).toISOString(),
  };
}

// ── 정적 서빙 ───────────────────────────────────────────
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json; charset=utf-8', '.css': 'text/css', '.wasm': 'application/wasm',
  '.data': 'application/octet-stream', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.unityweb': 'application/octet-stream',
};

async function serveStatic(req, res, urlPath) {
  // 경로 탈출 방지 — resolve 로 정규화한 뒤 ROOT 안에 있는지 본다.
  // (문자열 접두사 비교만 하면 `..` 과 구분자 차이로 뚫린다.)
  const safe = resolve(join(ROOT, decodeURIComponent(urlPath)));
  if (safe !== ROOT && !safe.startsWith(ROOT + sep)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  let target = safe;
  try {
    if ((await stat(target)).isDirectory()) target = join(target, 'index.html');
  } catch {
    res.writeHead(404).end('not found');
    return;
  }
  try {
    const body = await readFile(target);
    const headers = { 'Content-Type': MIME[extname(target).toLowerCase()] ?? 'application/octet-stream' };
    // Unity WebGL 은 브로틀리 산출물에 인코딩 헤더가 필요하다.
    if (target.endsWith('.br')) headers['Content-Encoding'] = 'br';
    if (target.endsWith('.gz')) headers['Content-Encoding'] = 'gzip';
    res.writeHead(200, headers).end(body);
  } catch {
    res.writeHead(404).end('not found');
  }
}

// ── 라우팅 ──────────────────────────────────────────────
const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

  if (url.pathname === '/api/v1/world-sessions') {
    if (req.method !== 'POST') { res.writeHead(405).end('method not allowed'); return; }
    const { token, jti, expiresAt } = issueGrant();
    const dto = {
      sessionId: `ws_mock_${jti}`,
      worldId: WORLD_ID,
      channelId: CHANNEL_ID,
      endpoint: { scheme: WORLD_SCHEME, host: WORLD_HOST, port: WORLD_PORT },
      connectionToken: token,
      expiresAt,
    };
    console.log(`[mock-api] world-session 발급 jti=${jti}`);
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(dto));
    return;
  }

  await serveStatic(req, res, url.pathname);
});

server.listen(PORT, () => {
  console.log(`[mock-api] http://localhost:${PORT}  root=${ROOT}`);
  console.log(`[mock-api] world-session → ${WORLD_SCHEME}://${WORLD_HOST}:${WORLD_PORT} (TTL ${TTL_SECONDS}s)`);
});
