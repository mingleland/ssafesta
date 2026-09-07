// AI Chat real API — spec 008 `contracts/conversation-api.yaml`. Spring과 별도 서버라
// shared/api/client의 api()를 재사용하지 않는다: base URL이 다르고, 메시지 엔드포인트는
// JSON이 아니라 SSE 본문(text/event-stream)을 그대로 흘려보내야 한다(S15P21A604-189).
import { getAccessToken } from '../../shared/api/client';
import { aiApiBaseUrl } from '../../shared/config/runtime';

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
  try {
    body = (await response.json()) as Partial<{ code: string; message: string }>;
  } catch {
    // 본문이 JSON이 아닌 응답(프록시 오류 등) — code/message 없이 status만으로 판단한다.
  }
  const retryAfterHeader = response.headers.get('Retry-After');
  const error: AiHttpError = {
    code: body.code ?? 'UNKNOWN',
    message: body.message ?? response.statusText,
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
): AsyncGenerator<string> {
  const response = await aiFetch(`/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ question }),
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
    reader.releaseLock();
  }
}
