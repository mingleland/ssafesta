// AI Chat real API — spec 008 `contracts/conversation-api.yaml`. Spring과 별도 서버라
// shared/api/client의 api()를 재사용하지 않는다: base URL이 다르고, 메시지 엔드포인트는
// JSON이 아니라 SSE 본문(text/event-stream)을 그대로 흘려보내야 한다(S15P21A604-189).
import { getAccessToken } from '../../shared/api/client';
import { aiApiBaseUrl } from '../../shared/config/runtime';
import { AI_ENDPOINT_UNREACHABLE } from './errorCodes';

export interface AiHttpError {
  code: string;
  message: string;
  status: number;
  // 429 응답의 Retry-After(초) — 없으면 서버가 안 준 것이다, 임의로 값을 지어내지 않는다.
  retryAfterSeconds?: number;
}

export function isAiHttpError(value: unknown): value is AiHttpError {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as AiHttpError).code === 'string' &&
    typeof (value as AiHttpError).status === 'number'
  );
}

async function aiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getAccessToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  return fetch(`${aiApiBaseUrl()}/ai/v1${path}`, { ...init, headers });
}

async function throwAiHttpError(response: Response): Promise<never> {
  let body: Partial<{ code: string; message: string }> = {};
  let contractEnvelope = false;
  try {
    body = (await response.json()) as Partial<{ code: string; message: string }>;
    contractEnvelope = true;
  } catch {
    // 본문이 JSON 이 아니다 — 계약 봉투가 아니라 그 자리에 AI 서버가 없다는 신호로 읽는다.
  }
  const retryAfterHeader = response.headers.get('Retry-After');
  const error: AiHttpError = {
    code: contractEnvelope ? (body.code ?? 'UNKNOWN') : AI_ENDPOINT_UNREACHABLE,
    message: body.message ?? (contractEnvelope ? response.statusText : `AI 서버에 닿지 못했습니다 (${aiApiBaseUrl()}/ai/v1)`),
    status: response.status,
    retryAfterSeconds: retryAfterHeader !== null ? Number(retryAfterHeader) : undefined,
  };
  throw error;
}

export interface ConversationHandle {
  conversationId: string;
  expiresAt: string;
}

export async function createConversation(
  boothId: number,
  agentId: number,
): Promise<ConversationHandle> {
  const response = await aiFetch('/conversations', {
    method: 'POST',
    body: JSON.stringify({ boothId, agentId }),
  });
  if (!response.ok) await throwAiHttpError(response);
  return (await response.json()) as ConversationHandle;
}

// SSE 본문을 텍스트 청크로만 흘려보낸다 — event/data 파싱은 entities/conversation/stream.parser가
// 한다(관심사 분리, mock 스트림과 동일한 소비 형태라 AiChatOverlay 쪽 변경을 최소화한다).
export async function* streamMessage(
  conversationId: string,
  question: string,
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const response = await aiFetch(`/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ question }),
    signal,
  });
  if (!response.ok || response.body === null) {
    await throwAiHttpError(response);
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) return;
      yield decoder.decode(value, { stream: true });
    }
  } finally {
    // releaseLock 만 하면 본문 스트림이 열린 채 남아 커넥션이 살아 있다 — 버려질 답변에
    // LLM 토큰과 커넥션을 전체 timeout(60초)까지 더 태운다(S15P21A604-516, 헌법 19조).
    // cancel 은 lock 을 풀면서 스트림까지 닫으므로, abort 없이 제너레이터를 중도 포기한
    // 경우(for await 이탈 → finally 실행)도 이 한 줄이 덮는다.
    // 이미 끊긴 스트림의 cancel 은 던질 수 있고 그건 정리 실패가 아니다 — 삼킨다.
    await reader.cancel().catch(() => {});
  }
}

/**
 * Conversation 원문 즉시 삭제 (S15P21A604-127 · spec 008 FR-028).
 *
 * 계약상 **204 하나만이 성공**이고 404 는 없다 — 없는 id 도 204 다(멱등). 그래서 종료 호출이
 * 재시도되거나 30분 유휴 TTL 과 경합해도 실패로 다루지 않는다. 본문이 없으므로 json() 을 부르지 않는다.
 */
export async function closeConversation(conversationId: string, signal?: AbortSignal): Promise<void> {
  const response = await aiFetch(`/conversations/${conversationId}`, { method: 'DELETE', signal });
  if (!response.ok) await throwAiHttpError(response);
}
