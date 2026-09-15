// AI Chat HTTP 단계(연결 전) 오류 안내 문구 — spec 008 FR-011 (S15P21A604-189).
// SSE error 이벤트(연결 후)·sequence 결번은 entities/conversation/stream.consumer가
// 이미 처리한다(S15P21A604-182) — 이 파일은 스트림 시작 전 실패(Conversation 생성·전송)만 다룬다.
import type { AiHttpError } from './api';
import { AI_ENDPOINT_UNREACHABLE } from './errorCodes';

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

// AI 서버가 그 자리에 없는 것은 대화 상태 문제가 아니다 — 새 Conversation 을 만들어도 같은 곳으로
// 간다. status 로만 갈랐을 때 404 가 reset 을 유발하던 것을 code 로 먼저 끊는다(S15P21A604-567).
export function shouldResetConversation(error: AiHttpError): boolean {
  if (error.code === AI_ENDPOINT_UNREACHABLE) return false;
  return CONVERSATION_RESET_STATUSES.has(error.status);
}

export function describeHttpError(error: AiHttpError): StreamErrorDescription {
  // 계약 봉투가 아닌 응답을 status 문구로 안내하면 사실과 달라진다 — 404 는 "대화가 만료되었습니다"
  // 로 나가지만 실제로는 AI 서버가 붙어 있지 않은 것이다. 그래서 code 를 먼저 본다.
  if (error.code === AI_ENDPOINT_UNREACHABLE) {
    return {
      headline: 'AI 상담 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.',
      retryable: true,
      retryAfterSeconds: error.retryAfterSeconds,
    };
  }
  return {
    headline: HTTP_STATUS_MESSAGES[error.status] ?? error.message,
    retryable: error.status === 429 || error.status === 503 || error.status >= 500,
    retryAfterSeconds: error.retryAfterSeconds,
  };
}
