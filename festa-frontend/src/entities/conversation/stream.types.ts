// spec 008 AI 대화 SSE 계약의 FE측 타입 사본 — Issue #32 최종 합의를 그대로 전사한다
// (front·develop의 로컬 docs/14·spec 008 사본은 #32 반영 전 상태로 낡아 있음 — 정본은 origin/ai
// 브랜치의 docs/14_AI_Server_API_명세서.md §6·§13, specs/008-ai-conversation-rag/spec.md C-08)
// 출처: https://github.com/kanghyunsoon/ssafesta/issues/32 (AI ghkim1632 최종 코멘트, 2026-08-23)

// SSE data.type 판별 태그 — SSE event: 라인과 항상 같아야 한다(#32 §4, 계약 위반 시 SseProtocolError)
export type SseEventType = 'start' | 'token' | 'source' | 'done' | 'error';

// 모든 이벤트 공통 envelope. sequence는 start=0부터 1씩 증가하는 무결성 검증 값이며
// 재개(resume)용 offset이 아니다 — 연결이 끊기면 새 요청으로 처리한다(자동 재연결 없음)
interface SseEnvelope {
  requestId: string;
  conversationId: string;
  messageId: string;
  sequence: number;
}

export interface SseStartEvent extends SseEnvelope {
  type: 'start';
}

export interface SseTokenEvent extends SseEnvelope {
  type: 'token';
  delta: string; // 빈 문자열은 서버가 보내지 않는다(계약)
}

export interface SseSourceEvent extends SseEnvelope {
  type: 'source';
  documentId: number;
  title: string;
  chunkId: string;
  // 선택 필드로 예약 — P0은 원문 접근 계약이 없어 생략, 문서명만 표시(#32 FE↔AI 합의)
  sourceUrl?: string;
}

export interface SseDoneEvent extends SseEnvelope {
  type: 'done';
  // P0은 플래그만 보관 — 상담 전환 UI는 spec 011(P1)
  handoffRecommended: boolean;
}

export interface SseErrorEvent extends SseEnvelope {
  type: 'error';
  code: AiErrorCode;
  message: string;
  retryable: boolean; // 이 값이 런타임 정본 — DEFAULT_RETRYABLE은 fallback일 뿐
  retryAfterSeconds?: number; // 재시도 시점을 계산할 수 있을 때만 포함
  timeoutPhase?: TimeoutPhase; // code === 'LLM_TIMEOUT'일 때만 의미 있음
}

export type SseStreamEvent =
  | SseStartEvent
  | SseTokenEvent
  | SseSourceEvent
  | SseDoneEvent
  | SseErrorEvent;

// LLM_TIMEOUT 하나를 유지하고 구간으로만 구분한다(#32 AI 결론)
// FIRST_TOKEN: 첫 token 15초 초과 / TOTAL_RESPONSE: 전체 응답 60초 초과
export type TimeoutPhase = 'FIRST_TOKEN' | 'TOTAL_RESPONSE';

// docs/14 Error Code 표 17종 — 순서는 표와 동일
export type AiErrorCode =
  | 'INVALID_REQUEST'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'BOOTH_NOT_FOUND'
  | 'AGENT_NOT_FOUND'
  | 'AGENT_DISABLED'
  | 'BOOTH_LEASE_EXPIRED'
  | 'CONVERSATION_NOT_FOUND'
  | 'DOCUMENT_NOT_FOUND'
  | 'DOCUMENT_NOT_READY'
  | 'DOCUMENT_PROCESSING_FAILED'
  | 'RAG_SEARCH_FAILED'
  | 'LLM_TIMEOUT'
  | 'LLM_PROVIDER_ERROR'
  | 'STREAM_CLOSED'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR';

// 계약상 기본값(mock fixture·문서 표 그대로) — 서버가 실제 event: error로 보내는 SseErrorEvent.retryable이
// 항상 정본이다. 이 테이블은 서버 payload가 없는 상황(mock, 참고용 fallback)에서만 쓴다.
export const DEFAULT_RETRYABLE: Record<AiErrorCode, boolean> = {
  INVALID_REQUEST: false,
  UNAUTHORIZED: false,
  FORBIDDEN: false,
  BOOTH_NOT_FOUND: false,
  AGENT_NOT_FOUND: false,
  AGENT_DISABLED: false,
  BOOTH_LEASE_EXPIRED: false,
  CONVERSATION_NOT_FOUND: false,
  DOCUMENT_NOT_FOUND: false,
  DOCUMENT_NOT_READY: true,
  DOCUMENT_PROCESSING_FAILED: false,
  RAG_SEARCH_FAILED: true,
  LLM_TIMEOUT: true,
  LLM_PROVIDER_ERROR: true,
  STREAM_CLOSED: true,
  RATE_LIMITED: true,
  INTERNAL_ERROR: true,
};

// 파서가 검출하는 "계약 위반" — 서버가 보낸 정상 SseErrorEvent와는 다른 층위라 SseStreamEvent
// union에 넣지 않는다. 서버 오류(에이전트가 실패를 알림)와 프로토콜 오류(응답이 계약을 안 지킴)를
// 같은 타입으로 섞으면 소비자가 "이 에러가 재시도 가능한지"를 오판할 수 있다.
export type SseProtocolErrorKind =
  | 'EVENT_TYPE_MISMATCH' // event: 라인과 data.type이 다름
  | 'UNKNOWN_EVENT' // 5종 밖의 event: 이름 (heartbeat/comment 제외)
  | 'MALFORMED_DATA'; // data가 JSON으로 파싱되지 않음

export class SseProtocolError extends Error {
  readonly kind: SseProtocolErrorKind;
  readonly raw: string; // 원인이 된 원문 프레임 텍스트

  constructor(kind: SseProtocolErrorKind, message: string, raw: string) {
    super(message);
    this.name = 'SseProtocolError';
    this.kind = kind;
    this.raw = raw;
  }
}
