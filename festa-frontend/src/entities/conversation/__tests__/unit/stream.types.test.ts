// DEFAULT_RETRYABLE 테이블이 docs/14(origin/ai 정본) Error Code 표 17종과 1:1인지 회귀 방어.
import { describe, expect, it } from 'vitest';
import { DEFAULT_RETRYABLE } from '../../stream.types';

// origin/ai:docs/14_AI_Server_API_명세서.md Error Code 표 그대로(2026-08-23 확인)
const EXPECTED: Record<string, boolean> = {
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

describe('DEFAULT_RETRYABLE', () => {
  it('17종 전부 계약 표와 1:1로 일치한다', () => {
    expect(DEFAULT_RETRYABLE).toEqual(EXPECTED);
  });

  it('false 10종 · true 7종', () => {
    const values = Object.values(DEFAULT_RETRYABLE);
    expect(values.filter((v) => v === false)).toHaveLength(10);
    expect(values.filter((v) => v === true)).toHaveLength(7);
  });
});
