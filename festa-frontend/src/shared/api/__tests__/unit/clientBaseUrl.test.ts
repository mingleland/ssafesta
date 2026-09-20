// api() 의 base 선택 — FE Host Gateway (S15P21A604-564).
// 일반 호출은 apiBaseUrl 로 가고, 인증 계열만 baseUrl 옵션으로 다른 호스트를 쓴다. 그 갈림이
// 401 재시도까지 살아남아야 한다 — 재시도가 base 를 잃으면 쿠키가 붙은 호스트를 벗어난다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, setAccessToken, setUnauthorizedHandler } from '../../client';

function makeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'status',
    headers: { get: () => null } as unknown as Headers,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

const fetchMock = vi.fn();
const requestedUrls = (): string[] => fetchMock.mock.calls.map((call) => call[0] as string);

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // 개발자마다 다른 .env.local 이 base 판정에 새어 들지 않게 비운다.
  vi.stubEnv('VITE_API_BASE_URL', '');
  setAccessToken(null);
  setUnauthorizedHandler(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('api() base 선택', () => {
  it('baseUrl 을 주면 그 base 로 나간다 — 인증 계열이 쓰는 경로', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(200, { ok: true }));

    await api('/api/v1/auth/refresh', { baseUrl: 'http://localhost:8080' });

    expect(requestedUrls()).toEqual(['http://localhost:8080/api/v1/auth/refresh']);
  });

  it('baseUrl 이 없으면 기본 base 를 쓴다 — 일반 호출 41곳은 그대로다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(200, { ok: true }));

    await api('/api/v1/users/me');

    expect(requestedUrls()[0].endsWith('/api/v1/users/me')).toBe(true);
    expect(requestedUrls()[0].startsWith('http://localhost:8080/')).toBe(false);
  });

  it('401 재시도도 같은 base 로 나간다 — 재시도가 호스트를 바꾸면 쿠키를 잃는다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    fetchMock.mockResolvedValueOnce(makeResponse(200, { ok: true }));
    setUnauthorizedHandler(vi.fn().mockResolvedValue(true));

    await api('/api/v1/users/me', { baseUrl: 'http://localhost:8080' });

    expect(requestedUrls()).toEqual([
      'http://localhost:8080/api/v1/users/me',
      'http://localhost:8080/api/v1/users/me',
    ]);
  });

  it('baseUrl 은 fetch init 으로 새어 나가지 않는다 — RequestInit 의 키가 아니다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(200, { ok: true }));

    await api('/api/v1/users/me', { baseUrl: 'http://localhost:8080' });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('baseUrl');
  });
});
