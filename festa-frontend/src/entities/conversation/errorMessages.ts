// AI Chat 오류 계열별 안내 문구 — spec 008 C-07/FR-011 (S15P21A604-189).
// HTTP 단계 오류(연결 전)와 SSE error 이벤트(연결 후)는 출처가 다르지만 사용자에게는 같은 모양의
// 안내(문구 + 재시도 가능 여부 + 대기 시간)로 보여야 해서 한 타입으로 모은다.
import type { AiHttpError } from './api';
import type { AiErrorCode, SseErrorEvent } from './stream.types';

export interface StreamErrorDescription {
  headline: string;
  retryable: boolean;
  retryAfterSeconds?: number;
}

const HTTP_STATUS_MESSAGES: Partial<Record<number, string>> = {
  401: '로그인이 필요합니다.',
  403: '이 부스의 AI 상담을 지금 이용할 수 없습니다.',
  404: '대화가 만료되었습니다. 다시 시작해주세요.',
  422: '요청을 처리할 수 없습니다.',
  429: '지금 대화 요청이 많습니다. 잠시 후 다시 시도해주세요.',
  503: '일시적으로 AI 상담을 시작할 수 없습니다. 잠시 후 다시 시도해주세요.',
};

// 새 Conversation을 만들어야 하는 오류 — 지금 대화를 계속 이어갈 수 없는 상태들.
const CONVERSATION_RESET_STATUSES = new Set([403, 404]);

export function shouldResetConversation(error: AiHttpError): boolean {
  return CONVERSATION_RESET_STATUSES.has(error.status);
}

export function describeHttpError(error: AiHttpError): StreamErrorDescription {
  return {
    headline: HTTP_STATUS_MESSAGES[error.status] ?? error.message,
    retryable: error.status === 429 || error.status === 503 || error.status >= 500,
    retryAfterSeconds: error.retryAfterSeconds,
  };
}

const SSE_ERROR_MESSAGES: Partial<Record<AiErrorCode, string>> = {
  LLM_TIMEOUT: '답변 생성이 지연되고 있습니다.',
  LLM_PROVIDER_ERROR: 'AI 응답 생성에 실패했습니다.',
  RAG_SEARCH_FAILED: '자료 검색에 실패했습니다.',
  RATE_LIMITED: '지금 대화 요청이 많습니다.',
  STREAM_CLOSED: '연결이 끊겼습니다.',
  BOOTH_LEASE_EXPIRED: '부스 임대가 만료되어 대화를 이어갈 수 없습니다.',
  DOCUMENT_NOT_READY: '아직 상담 자료가 준비되지 않았습니다.',
  INTERNAL_ERROR: '일시적인 오류가 발생했습니다.',
};

function timeoutSuffix(event: SseErrorEvent): string {
  if (event.code !== 'LLM_TIMEOUT' || event.timeoutPhase === undefined) return '';
  return event.timeoutPhase === 'FIRST_TOKEN' ? ' (첫 응답 지연)' : ' (전체 응답 지연)';
}

export function describeSseError(event: SseErrorEvent): StreamErrorDescription {
  const headline = (SSE_ERROR_MESSAGES[event.code] ?? event.message) + timeoutSuffix(event);
  return {
    headline,
    retryable: event.retryable,
    retryAfterSeconds: event.retryAfterSeconds,
  };
}

export function describeProtocolError(): StreamErrorDescription {
  return { headline: '연결이 중간에 끊겼습니다. 다시 시도해주세요.', retryable: true };
}

export function describeSequenceGap(): StreamErrorDescription {
  return { headline: '응답이 잘린 것 같습니다. 다시 시도해주세요.', retryable: true };
}
