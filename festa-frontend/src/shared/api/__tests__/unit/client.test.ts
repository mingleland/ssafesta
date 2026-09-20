// client.ts 401 인터셉트 오케스트레이션 회귀 방어(T011) — 실 refresh 로직(features/auth)과 분리해
// "핸들러가 true/false를 반환했을 때 client.ts가 정확히 1회만 재시도한다"는 계약만 검증한다.
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

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken(null);
  setUnauthorizedHandler(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('401 인터셉트', () => {
  it('핸들러가 true를 반환하면 원요청을 1회 재시도해 성공 응답을 돌려준다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    fetchMock.mockResolvedValueOnce(makeResponse(200, { ok: true }));
    const handler = vi.fn().mockResolvedValue(true);
    setUnauthorizedHandler(handler);

    const result = await api('/x');

    expect(result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('핸들러가 false를 반환하면 재시도 없이 원 오류를 던진다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    const handler = vi.fn().mockResolvedValue(false);
    setUnauthorizedHandler(handler);

    await expect(api('/x')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('재시도 응답이 다시 401이어도 핸들러를 또 부르지 않는다(상한 1회)', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'y' }));
    const handler = vi.fn().mockResolvedValue(true);
    setUnauthorizedHandler(handler);

    await expect(api('/x')).rejects.toMatchObject({ code: 'UNAUTHORIZED', message: 'y' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('skipAuthRetry:true로 호출하면(refresh 자기 자신 등) 401이어도 핸들러를 부르지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));
    const handler = vi.fn().mockResolvedValue(true);
    setUnauthorizedHandler(handler);

    await expect(api('/x', { skipAuthRetry: true })).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(handler).not.toHaveBeenCalled();
  });

  it('핸들러가 등록되지 않았으면 기존과 동일하게 즉시 오류를 던진다', async () => {
    fetchMock.mockResolvedValueOnce(makeResponse(401, { code: 'UNAUTHORIZED', message: 'x' }));

    await expect(api('/x')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
