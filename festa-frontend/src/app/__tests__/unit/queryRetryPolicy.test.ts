// 전역 재시도 정책 (S15P21A604-458, GitLab #139) — 서버가 정확히 거절한 것은 다시 묻지 않는다.
//
// 이 테스트가 있는 이유: 다른 테스트들은 각자 `retry: false` QueryClient 를 새로 만들어 쓴다.
// 그래서 **프로덕션 QueryClient 의 재시도 동작을 구조적으로 아무도 보지 않았다** — 게스트 403 이
// 20초에 40건으로 번진 것이 그 사각에서 나왔다. 여기서 프로덕션 인스턴스를 직접 잡아 고정한다.
import { describe, expect, it } from 'vitest';
import { queryClient } from '../../providers/queryClient';
import type { ApiError } from '../../../shared/api/client';

const envelope = (code: string, status?: number): ApiError => ({
  code,
  message: 'x',
  status,
  errors: [],
  warnings: [],
});

// 프로덕션 클라이언트가 실제로 쓰는 retry 를 그대로 꺼내 부른다
const retry = queryClient.getDefaultOptions().queries?.retry;
const shouldRetry = (error: unknown, failureCount = 0): boolean => {
  if (typeof retry !== 'function') throw new Error('retry 가 함수가 아니다 — 정책이 사라졌다');
  return retry(failureCount, error) as boolean;
};

describe('전역 재시도 정책 (-458)', () => {
  it('403 MEMBER_ONLY 는 재시도하지 않는다 — #139 가 보고한 그 요청이다', () => {
    expect(shouldRetry(envelope('MEMBER_ONLY', 403))).toBe(false);
  });

  it('4xx 는 코드와 무관하게 재시도하지 않는다', () => {
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(shouldRetry(envelope('ANY', status))).toBe(false);
    }
  });

  it('408·429 는 재시도한다 — 4xx 지만 시간이 답을 바꾼다', () => {
    expect(shouldRetry(envelope('TIMEOUT', 408))).toBe(true);
    expect(shouldRetry(envelope('TOO_MANY', 429))).toBe(true);
  });

  it('5xx 는 재시도한다 — 서버 사정이라 다시 물을 값이 있다', () => {
    expect(shouldRetry(envelope('INTERNAL_ERROR', 500))).toBe(true);
    expect(shouldRetry(envelope('BAD_GATEWAY', 502))).toBe(true);
  });

  it('상태를 모르는 오류는 재시도한다 — 네트워크 단절·mock 봉투', () => {
    expect(shouldRetry(new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(envelope('UNKNOWN'))).toBe(true);
  });

  it('재시도 상한은 3회다 — 5xx 라도 무한히 물지 않는다', () => {
    expect(shouldRetry(envelope('INTERNAL_ERROR', 500), 2)).toBe(true);
    expect(shouldRetry(envelope('INTERNAL_ERROR', 500), 3)).toBe(false);
  });
});
