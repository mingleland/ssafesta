// SSE consumer의 누적 렌더 상태·종료 오류·sequence 결번 처리를 계약 fixture로 검증한다.
import { describe, expect, it, vi } from 'vitest';
import { mockStreamError, mockStreamSequenceGap, mockStreamSuccess } from '../../stream.mock';
import { consumeSseStream } from '../../stream.consumer';

describe('consumeSseStream', () => {
  it('token을 순서대로 누적하고 source를 표시한 뒤 done으로 끝낸다', async () => {
    const updates: string[] = [];
    const result = await consumeSseStream(
      mockStreamSuccess(['첫째 ', '둘째']),
      (state) => updates.push(`${state.status}:${state.text}:${state.sources.join(',')}`),
    );

    expect(updates).toContain('streaming:첫째 :');
    expect(updates).toContain('streaming:첫째 둘째:프로젝트_기획서.pdf');
    expect(result).toMatchObject({
      status: 'done',
      text: '첫째 둘째',
      sources: ['프로젝트_기획서.pdf'],
    });
  });

  it('error envelope의 메시지와 retryable을 보존한다', async () => {
    const result = await consumeSseStream(mockStreamError(), vi.fn());
    expect(result).toMatchObject({
      status: 'error',
      errorMessage: 'AI 응답 오류: LLM_TIMEOUT',
      retryable: true,
    });
  });

  it('종료 sequence 결번이면 잘린 응답으로 표시하고 식별자만 경고한다', async () => {
    const warn = vi.fn();
    const result = await consumeSseStream(mockStreamSequenceGap(), vi.fn(), warn);

    expect(result).toMatchObject({
      status: 'truncated',
      errorMessage: '응답이 중간에 끊겼습니다. 다시 시도해 주세요.',
      retryable: true,
    });
    expect(warn).toHaveBeenCalledWith({
      requestId: 'req_mock_01',
      expected: 2,
      actual: 3,
      terminal: true,
    });
  });
});
