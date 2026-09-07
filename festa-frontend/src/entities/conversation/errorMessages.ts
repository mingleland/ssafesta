// AI Chat HTTP 단계(연결 전) 오류 안내 문구 — spec 008 FR-011 (S15P21A604-189).
// SSE error 이벤트(연결 후)·sequence 결번은 entities/conversation/stream.consumer가
// 이미 처리한다(S15P21A604-182) — 이 파일은 스트림 시작 전 실패(Conversation 생성·전송)만 다룬다.
import type { AiHttpError } from './api';

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
