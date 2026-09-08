// entities/conversation/api real API 계약 검증 (S15P21A604-189) — mock stream을 대체하는 실제
// fetch 결선. base URL·Authorization 헤더·오류 매핑·SSE 청크 통과만 본다(파싱은 stream.parser 몫).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../../../../shared/api/client';
import { closeConversation, createConversation, isAiHttpError, streamMessage } from '../../api';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'status',
    headers: { get: (name: string) => headers[name] ?? null } as unknown as Headers,
    json: () => Promise.resolve(body),
    body: null,
  } as unknown as Response;
}

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('createConversation', () => {
  it('AI 서버로 boothId+agentId를 담아 POST하고 결과를 돌려준다', async () => {
    setAccessToken('token-abc');
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, { conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' }),
    );

    const result = await createConversation(7, 3);

    expect(result).toEqual({ conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/ai\/v1\/conversations$/);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ boothId: 7, agentId: 3 });
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer token-abc');
  });

  it('403 응답을 code/message/status를 담은 오류로 던진다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(403, { code: 'BOOTH_LEASE_EXPIRED', message: '임대가 만료되었습니다.' }),
    );

    await expect(createConversation(7, 3)).rejects.toMatchObject({
      code: 'BOOTH_LEASE_EXPIRED',
      status: 403,
    });
  });

  it('오류 본문이 없어도(프록시 오류 등) UNKNOWN 코드로 던진다', async () => {
    const response = jsonResponse(502, {});
    response.json = () => Promise.reject(new Error('not json'));
    fetchMock.mockResolvedValueOnce(response);

    await expect(createConversation(7, 3)).rejects.toMatchObject({ code: 'UNKNOWN', status: 502 });
  });

  it('429는 Retry-After 헤더를 초로 파싱해 함께 던진다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(429, { code: 'RATE_LIMITED', message: '잠시 후 다시 시도해주세요.' }, { 'Retry-After': '10' }),
    );

    await expect(createConversation(7, 3)).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      status: 429,
      retryAfterSeconds: 10,
    });
  });
});

describe('streamMessage', () => {
  // 실 ReadableStreamDefaultReader 와 같은 모양이어야 한다 — cancel 이 없으면 정리 경로가
  // 테스트에서만 다르게 동작한다(S15P21A604-516 에서 releaseLock 을 cancel 로 바꿨다).
  function streamingBody(chunks: string[]) {
    let index = 0;
    const cancel = vi.fn(() => Promise.resolve());
    const body = {
      getReader: () => ({
        read: () => {
          if (index >= chunks.length) return Promise.resolve({ done: true, value: undefined });
          const value = new TextEncoder().encode(chunks[index]);
          index += 1;
          return Promise.resolve({ done: false, value });
        },
        releaseLock: () => {},
        cancel,
      }),
    };
    return { body, cancel };
  }

  it('SSE 본문 청크를 순서대로 문자열로 흘려보낸다', async () => {
    const { body, cancel } = streamingBody(['event: start\ndata: {}\n\n', 'event: done\ndata: {}\n\n']);
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, body } as unknown as Response);

    const received: string[] = [];
    for await (const chunk of streamMessage('conv_1', '질문입니다')) {
      received.push(chunk);
    }

    expect(received).toEqual(['event: start\ndata: {}\n\n', 'event: done\ndata: {}\n\n']);
    // 다 읽은 뒤에도 스트림을 닫는다 — releaseLock 만 하면 커넥션이 남는다.
    expect(cancel).toHaveBeenCalled();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/ai\/v1\/conversations\/conv_1\/messages$/);
    expect(JSON.parse(init.body as string)).toEqual({ question: '질문입니다' });
  });

  it('중도에 포기해도 스트림을 닫는다 — 버려질 답변에 커넥션을 더 태우지 않는다', async () => {
    const { body, cancel } = streamingBody(['a', 'b', 'c']);
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, body } as unknown as Response);

    // 첫 청크만 받고 이탈한다(break) → 제너레이터의 finally 가 돈다.
    for await (const _chunk of streamMessage('conv_1', '질문')) break;

    expect(cancel).toHaveBeenCalled();
  });

  it('signal 을 fetch 로 넘긴다', async () => {
    const { body } = streamingBody([]);
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, body } as unknown as Response);
    const controller = new AbortController();

    for await (const _chunk of streamMessage('conv_1', '질문', controller.signal)) break;

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBe(controller.signal);
  });

  it('연결 전 HTTP 오류(404 등)는 스트림을 시작하지 않고 오류를 던진다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(404, { code: 'CONVERSATION_NOT_FOUND', message: 'Conversation을 찾을 수 없습니다.' }),
    );

    const iterator = streamMessage('conv_missing', '질문')[Symbol.asyncIterator]();
    await expect(iterator.next()).rejects.toMatchObject({
      code: 'CONVERSATION_NOT_FOUND',
      status: 404,
    });
  });
});

describe('closeConversation', () => {
  it('DELETE 로 부르고 204 면 조용히 끝난다 — 본문을 읽지 않는다', async () => {
    // 204 는 body 가 없다. json() 을 부르면 던지므로 아예 부르지 않는 것이 계약이다.
    setAccessToken('token-abc');
    const response = jsonResponse(204, undefined);
    response.json = () => Promise.reject(new Error('204 has no body'));
    fetchMock.mockResolvedValueOnce(response);

    await expect(closeConversation('conv_1')).resolves.toBeUndefined();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/ai\/v1\/conversations\/conv_1$/);
    expect(init.method).toBe('DELETE');
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer token-abc');
  });

  it('403 은 code/status 를 담은 오류로 던진다 — 다른 사용자 Conversation', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(403, { code: 'CONVERSATION_OWNERSHIP_MISMATCH', message: '다른 사용자의 대화입니다.' }),
    );

    await expect(closeConversation('conv_1')).rejects.toMatchObject({
      code: 'CONVERSATION_OWNERSHIP_MISMATCH',
      status: 403,
    });
  });

  it('signal 을 fetch 로 넘긴다 — 언마운트 정리에서 함께 끊을 수 있어야 한다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined));
    const controller = new AbortController();

    await closeConversation('conv_1', controller.signal);

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBe(controller.signal);
  });
});

describe('isAiHttpError', () => {
  it('code와 status를 가진 값만 인정한다', () => {
    expect(isAiHttpError({ code: 'X', status: 400, message: 'm' })).toBe(true);
    expect(isAiHttpError(new Error('boom'))).toBe(false);
    expect(isAiHttpError(null)).toBe(false);
  });
});
