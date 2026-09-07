// @vitest-environment jsdom
// 실서버 결선 회귀 방어 (S15P21A604-189) — Conversation 생성·SSE 왕복·오류 계열별 UX를
// entities/conversation/api를 mock해 검증한다. 파서·프레임 계약 자체는 stream.parser.test.ts 몫.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiChatOverlay } from '../../AiChatOverlay';

const createConversation = vi.fn();
const streamMessage = vi.fn();

vi.mock('../../../../../entities/conversation/api', () => ({
  createConversation: (...args: unknown[]) => createConversation(...args),
  streamMessage: (...args: unknown[]) => streamMessage(...args),
  isAiHttpError: (value: unknown): boolean =>
    typeof value === 'object' && value !== null && typeof (value as { status?: unknown }).status === 'number',
}));

function frame(type: string, data: Record<string, unknown>): string {
  return `event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`;
}

async function* successStream(): AsyncGenerator<string> {
  const env = { requestId: 'req_1', conversationId: 'conv_1', messageId: 'msg_1' };
  yield frame('start', { ...env, sequence: 0 });
  yield frame('token', { ...env, sequence: 1, delta: '안녕' });
  yield frame('token', { ...env, sequence: 2, delta: '하세요' });
  yield frame('source', { ...env, sequence: 3, documentId: 1, chunkId: 'c1', title: '문서.pdf' });
  yield frame('done', { ...env, sequence: 4, handoffRecommended: false });
}

async function* errorStream(): AsyncGenerator<string> {
  const env = { requestId: 'req_1', conversationId: 'conv_1', messageId: 'msg_1' };
  yield frame('start', { ...env, sequence: 0 });
  yield frame('error', {
    ...env,
    sequence: 1,
    code: 'LLM_TIMEOUT',
    message: '시간 초과',
    retryable: true,
    timeoutPhase: 'FIRST_TOKEN',
  });
}

beforeEach(() => {
  createConversation.mockReset();
  streamMessage.mockReset();
  // jsdom은 Element.scrollTo를 구현하지 않는다 — 대화창 자동 스크롤 effect가 던지지 않게만 막는다.
  Element.prototype.scrollTo = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function ask(question: string): void {
  fireEvent.change(screen.getByPlaceholderText('부스에 대해 물어보세요'), {
    target: { value: question },
  });
  fireEvent.click(screen.getByRole('button', { name: '보내기' }));
}

describe('AiChatOverlay 실서버 결선', () => {
  it('질문을 보내면 Conversation을 만들고 스트리밍 답변을 누적해 보여준다', async () => {
    createConversation.mockResolvedValue({ conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' });
    streamMessage.mockReturnValue(successStream());

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('안녕하세요?');

    await waitFor(() => expect(screen.getByText('안녕하세요')).toBeTruthy());
    expect(createConversation).toHaveBeenCalledWith(7, 3);
    expect(streamMessage).toHaveBeenCalledWith('conv_1', '안녕하세요?');
    expect(screen.getByText('문서.pdf')).toBeTruthy();
  });

  it('같은 대화에서 두 번째 질문은 Conversation을 다시 만들지 않는다', async () => {
    createConversation.mockResolvedValue({ conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' });
    streamMessage.mockReturnValueOnce(successStream()).mockReturnValueOnce(successStream());

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('첫 질문');
    await waitFor(() => expect(screen.getAllByText('안녕하세요')).toHaveLength(1));

    ask('두 번째 질문');
    await waitFor(() => expect(screen.getAllByText('안녕하세요')).toHaveLength(2));

    expect(createConversation).toHaveBeenCalledTimes(1);
  });

  it('Conversation 생성 실패(503)는 재시도 가능한 오류로 보여준다', async () => {
    createConversation.mockRejectedValue({ code: 'SPRING_UNAVAILABLE', message: '실패', status: 503 });

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');

    await waitFor(() =>
      expect(screen.getByText('일시적으로 AI 상담을 시작할 수 없습니다. 잠시 후 다시 시도해주세요.')).toBeTruthy(),
    );
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    expect(streamMessage).not.toHaveBeenCalled();
  });

  it('스트림 중 error 이벤트는 지연 안내와 함께 재시도 버튼을 보여준다', async () => {
    createConversation.mockResolvedValue({ conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' });
    streamMessage.mockReturnValue(errorStream());

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');

    await waitFor(() => expect(screen.getByText(/첫 응답 지연/)).toBeTruthy());
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('agentId가 없으면 서버를 부르지 않고 바로 안내한다', async () => {
    render(<AiChatOverlay payload={{ boothId: 7 }} />);
    ask('질문');

    await waitFor(() =>
      expect(screen.getByText('AI 직원 정보를 확인할 수 없습니다.')).toBeTruthy(),
    );
    expect(createConversation).not.toHaveBeenCalled();
  });
});
