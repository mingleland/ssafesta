// AI 호출의 base 계약과 "그 자리에 AI 서버가 없다" 판정 (S15P21A604-567).
//
// 잠그는 것 둘.
//   ① base 가 어디서 오든 경로는 `/ai/v1/...` 하나다 — 게이트웨이든 별도 호스트든 소비처 코드가 같다.
//   ② 계약 봉투가 아닌 응답을 status 문구로 안내하지 않는다. `/ai/v1` 이 아무 데도 안 붙어 있으면
//      dev 서버가 404 를 주는데(실측), 그것을 "대화가 만료되었습니다" 로 말하면 거짓말이 된다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { closeConversation, createConversation, streamMessage } from '../../api';
import { AI_ENDPOINT_UNREACHABLE } from '../../errorCodes';
import { describeHttpError, shouldResetConversation } from '../../errorMessages';

const fetchMock = vi.fn();
const requestedUrl = (): string => fetchMock.mock.calls[0][0] as string;

/** 계약 봉투(JSON)를 주는 응답 */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'status',
    headers: { get: () => null } as unknown as Headers,
    json: () => Promise.resolve(body),
    body: null,
  } as unknown as Response;
}

/** 계약 봉투가 아닌 응답 — dev 서버 404·프록시 오류 페이지처럼 JSON 이 아니다 */
function nonJsonResponse(status: number): Response {
  return {
    ok: false,
    status,
    statusText: 'Not Found',
    headers: { get: () => null } as unknown as Headers,
    json: () => Promise.reject(new SyntaxError('Unexpected token')),
    body: null,
  } as unknown as Response;
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  vi.stubEnv('VITE_AI_API_BASE_URL', '');
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('AI base 계약', () => {
  it('base 가 없으면 상대 경로로 나간다 — 게이트웨이가 흡수한다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { conversationId: 'c1', expiresAt: 'x' }));

    await createConversation(1, 2);

    expect(requestedUrl()).toBe('/ai/v1/conversations');
  });

  it('base 가 지정되면 그 앞에 붙고 경로는 그대로다 — 소비처 코드가 바뀌지 않는다', async () => {
    vi.stubEnv('VITE_AI_API_BASE_URL', 'https://ai.example.test');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { conversationId: 'c1', expiresAt: 'x' }));

    await createConversation(1, 2);

    expect(requestedUrl()).toBe('https://ai.example.test/ai/v1/conversations');
  });

  it('스트리밍 경로도 같은 base 를 쓴다 — SSE 만 다른 곳으로 새지 않는다', async () => {
    vi.stubEnv('VITE_AI_API_BASE_URL', 'https://ai.example.test');
    fetchMock.mockResolvedValueOnce(nonJsonResponse(404));

    // 첫 청크를 요구하는 순간 요청이 나간다 — 여기서는 URL 만 확인하므로 실패해도 된다.
    await expect(streamMessage('c1', '질문').next()).rejects.toMatchObject({ status: 404 });
    expect(requestedUrl()).toBe('https://ai.example.test/ai/v1/conversations/c1/messages');
  });

  it('종료 호출도 같은 base 를 쓴다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(204, null));

    await closeConversation('c1');

    expect(requestedUrl()).toBe('/ai/v1/conversations/c1');
  });
});

describe('AI 서버 미연결 판정', () => {
  it('계약 봉투가 아닌 응답은 AI_ENDPOINT_UNREACHABLE 이다', async () => {
    fetchMock.mockResolvedValueOnce(nonJsonResponse(404));

    await expect(createConversation(1, 2)).rejects.toMatchObject({
      code: AI_ENDPOINT_UNREACHABLE,
      status: 404,
    });
  });

  it('계약 봉투면 서버가 준 code 를 그대로 쓴다 — 정상 오류를 미연결로 오판하지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(404, { code: 'CONVERSATION_NOT_FOUND', message: 'x' }));

    await expect(createConversation(1, 2)).rejects.toMatchObject({
      code: 'CONVERSATION_NOT_FOUND',
      status: 404,
    });
  });

  it('미연결을 "대화가 만료되었습니다" 로 안내하지 않는다', () => {
    const described = describeHttpError({
      code: AI_ENDPOINT_UNREACHABLE,
      message: 'AI 서버에 닿지 못했습니다',
      status: 404,
    });

    expect(described.headline).not.toContain('만료');
    expect(described.headline).toContain('연결');
    expect(described.retryable).toBe(true);
  });

  it('미연결에서는 새 Conversation 을 만들지 않는다 — 같은 곳으로 다시 간다', () => {
    expect(shouldResetConversation({ code: AI_ENDPOINT_UNREACHABLE, message: 'x', status: 404 })).toBe(false);
    expect(shouldResetConversation({ code: 'CONVERSATION_NOT_FOUND', message: 'x', status: 404 })).toBe(true);
  });
});
