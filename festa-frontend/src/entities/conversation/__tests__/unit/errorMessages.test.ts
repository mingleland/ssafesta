import { describe, expect, it } from 'vitest';
import type { AiHttpError } from '../../api';
import { describeHttpError, shouldResetConversation } from '../../errorMessages';

function httpError(status: number, overrides: Partial<AiHttpError> = {}): AiHttpError {
  return { code: 'X', message: '원본 메시지', status, ...overrides };
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
