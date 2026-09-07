import { describe, expect, it } from 'vitest';
import type { AiHttpError } from '../../api';
import {
  describeHttpError,
  describeProtocolError,
  describeSequenceGap,
  describeSseError,
  shouldResetConversation,
} from '../../errorMessages';
import type { SseErrorEvent } from '../../stream.types';

function httpError(status: number, overrides: Partial<AiHttpError> = {}): AiHttpError {
  return { code: 'X', message: '원본 메시지', status, ...overrides };
}

function sseError(overrides: Partial<SseErrorEvent> = {}): SseErrorEvent {
  return {
    type: 'error',
    requestId: 'req_1',
    conversationId: 'conv_1',
    messageId: 'msg_1',
    sequence: 3,
    code: 'INTERNAL_ERROR',
    message: '원본 메시지',
    retryable: false,
    ...overrides,
  };
}

describe('describeHttpError', () => {
  it('429는 재시도 가능하고 Retry-After를 그대로 전달한다', () => {
    const result = describeHttpError(httpError(429, { retryAfterSeconds: 12 }));
    expect(result).toMatchObject({ retryable: true, retryAfterSeconds: 12 });
  });

  it('404는 재시도 불가로 표시한다 — 대화가 만료된 것이라 같은 conversationId로 재시도해도 소용없다', () => {
    const result = describeHttpError(httpError(404));
    expect(result.retryable).toBe(false);
  });

  it('알려지지 않은 상태 코드는 서버 message를 그대로 보여준다', () => {
    const result = describeHttpError(httpError(418, { message: '커스텀 메시지' }));
    expect(result.headline).toBe('커스텀 메시지');
  });
});

describe('shouldResetConversation', () => {
  it('403·404는 conversation을 새로 만들어야 한다', () => {
    expect(shouldResetConversation(httpError(403))).toBe(true);
    expect(shouldResetConversation(httpError(404))).toBe(true);
  });

  it('429·503은 같은 conversation으로 재시도한다', () => {
    expect(shouldResetConversation(httpError(429))).toBe(false);
    expect(shouldResetConversation(httpError(503))).toBe(false);
  });
});

describe('describeSseError', () => {
  it('서버 retryable 값을 그대로 신뢰한다', () => {
    const result = describeSseError(sseError({ retryable: true }));
    expect(result.retryable).toBe(true);
  });

  it('LLM_TIMEOUT + FIRST_TOKEN은 첫 응답 지연 안내를 덧붙인다', () => {
    const result = describeSseError(
      sseError({ code: 'LLM_TIMEOUT', timeoutPhase: 'FIRST_TOKEN', message: 'timeout' }),
    );
    expect(result.headline).toContain('첫 응답 지연');
  });

  it('LLM_TIMEOUT + TOTAL_RESPONSE는 전체 응답 지연 안내를 덧붙인다', () => {
    const result = describeSseError(
      sseError({ code: 'LLM_TIMEOUT', timeoutPhase: 'TOTAL_RESPONSE', message: 'timeout' }),
    );
    expect(result.headline).toContain('전체 응답 지연');
  });

  it('알려진 code는 고정 문구, 모르는 code는 서버 message를 쓴다', () => {
    expect(describeSseError(sseError({ code: 'BOOTH_LEASE_EXPIRED' })).headline).toBe(
      '부스 임대가 만료되어 대화를 이어갈 수 없습니다.',
    );
    expect(describeSseError(sseError({ code: 'AGENT_DISABLED', message: '서버 문구' })).headline).toBe(
      '서버 문구',
    );
  });
});

describe('protocol/sequence 안내', () => {
  it('둘 다 재시도 가능으로 표시한다 — 사용자 재입력이 유일한 복구 경로다', () => {
    expect(describeProtocolError().retryable).toBe(true);
    expect(describeSequenceGap().retryable).toBe(true);
  });
});
