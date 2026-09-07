// @vitest-environment jsdom
// 실서버 결선(S15P21A604-189) + SSE 렌더링(S15P21A604-182) 통합 회귀 방어.
// entities/conversation/api를 mock해 Conversation 생성·스트리밍을 대체하고, 실제 SSE 프레임은
// entities/conversation/stream.mock의 검증된 fixture를 그대로 재사용한다(파서·consumer 자체
// 계약은 stream.parser.test.ts·stream.consumer.test.ts 몫).
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockStreamError, mockStreamSequenceGap, mockStreamSuccess } from '../../../../../entities/conversation/stream.mock';
import { AiChatOverlay } from '../../AiChatOverlay';

const createConversation = vi.fn();
const streamMessage = vi.fn();
const closeConversation = vi.fn();

vi.mock('../../../../../entities/conversation/api', () => ({
  createConversation: (...args: unknown[]) => createConversation(...args),
  streamMessage: (...args: unknown[]) => streamMessage(...args),
  closeConversation: (...args: unknown[]) => closeConversation(...args),
  isAiHttpError: (value: unknown): boolean =>
    typeof value === 'object' && value !== null && typeof (value as { status?: unknown }).status === 'number',
}));

beforeEach(() => {
  createConversation.mockReset();
  createConversation.mockResolvedValue({ conversationId: 'conv_1', expiresAt: '2026-09-07T00:00:00Z' });
  streamMessage.mockReset();
  closeConversation.mockReset();
  closeConversation.mockResolvedValue(undefined);
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

function askFirstSuggestion(): void {
  render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
  fireEvent.click(screen.getByRole('button', { name: '어떤 프로젝트를 전시하나요?' }));
}

describe('AiChatOverlay 실서버 결선·SSE 렌더링', () => {
  it('token 누적 답변과 source 문서명을 done 뒤 표시한다', async () => {
    streamMessage.mockReturnValue(mockStreamSuccess());
    askFirstSuggestion();

    expect(await screen.findByText(/안녕하세요, 무엇을 도와드릴까요?/)).toBeTruthy();
    expect(await screen.findByText('프로젝트_기획서.pdf')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(createConversation).toHaveBeenCalledWith(7, 3);
  });

  it('같은 대화에서 두 번째 질문은 Conversation을 다시 만들지 않는다', async () => {
    // mockReturnValue 로 주면 두 질문이 **같은 제너레이터 인스턴스**를 공유해 두 번째가 빈 스트림이 된다
    // (실측: 1회차 'ab', 2회차 ''). 질문마다 새 스트림을 여는 실제 동작과 맞추려면 factory 여야 한다.
    streamMessage.mockImplementation(() => mockStreamSuccess());
    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);

    ask('첫 질문');
    await waitFor(() => expect(screen.getAllByText(/안녕하세요, 무엇을 도와드릴까요?/)).toHaveLength(1));
    ask('두 번째 질문');
    await waitFor(() => expect(screen.getAllByText(/안녕하세요, 무엇을 도와드릴까요?/)).toHaveLength(2));

    expect(createConversation).toHaveBeenCalledTimes(1);
  });

  it('error envelope의 메시지와 재시도 UX를 표시한다', async () => {
    const factory = vi.fn(() => mockStreamError());
    streamMessage.mockImplementation(factory);
    askFirstSuggestion();

    expect((await screen.findByRole('alert')).textContent).toContain('AI 응답 오류: LLM_TIMEOUT');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(factory).toHaveBeenCalledTimes(2));
    // 재시도는 이미 만든 Conversation을 재사용한다 — error/truncated는 conversation 자체 문제가 아니다.
    expect(createConversation).toHaveBeenCalledTimes(1);
  });

  it('종료 sequence 결번을 잘린 응답으로 안내한다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    streamMessage.mockReturnValue(mockStreamSequenceGap());
    askFirstSuggestion();

    expect((await screen.findByRole('alert')).textContent).toContain('응답이 중간에 끊겼습니다');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('Conversation 생성 실패(503)는 재시도 가능한 오류로 보여주고 스트림을 시작하지 않는다', async () => {
    createConversation.mockReset();
    createConversation.mockRejectedValue({ code: 'SPRING_UNAVAILABLE', message: '실패', status: 503 });

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');

    expect(
      (await screen.findByRole('alert')).textContent,
    ).toContain('일시적으로 AI 상담을 시작할 수 없습니다. 잠시 후 다시 시도해주세요.');
    expect(streamMessage).not.toHaveBeenCalled();
  });

  it('agentId가 없으면 서버를 부르지 않고 바로 안내한다', async () => {
    render(<AiChatOverlay payload={{ boothId: 7 }} />);
    ask('질문');

    expect((await screen.findByRole('alert')).textContent).toContain('AI 직원 정보를 확인할 수 없습니다.');
    expect(createConversation).not.toHaveBeenCalled();
  });

  it('오버레이가 사라지면 Conversation 을 즉시 삭제한다 (S15P21A604-516)', async () => {
    streamMessage.mockImplementation(() => mockStreamSuccess());
    const { unmount } = render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');
    await screen.findByText(/안녕하세요, 무엇을 도와드릴까요?/);

    unmount();

    expect(closeConversation).toHaveBeenCalledWith('conv_1');
  });

  it('대화를 만들지 않고 닫으면 삭제를 부르지 않는다 — 지울 것이 없다', async () => {
    const { unmount } = render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    unmount();
    expect(closeConversation).not.toHaveBeenCalled();
  });

  it('삭제가 실패해도 조용하다 — 화면은 이미 닫혔고 30분 TTL 이 지운다', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    closeConversation.mockRejectedValue({ code: 'CONVERSATION_OWNERSHIP_MISMATCH', status: 403 });
    streamMessage.mockImplementation(() => mockStreamSuccess());
    const { unmount } = render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');
    await screen.findByText(/안녕하세요, 무엇을 도와드릴까요?/);

    expect(() => unmount()).not.toThrow();
    await Promise.resolve();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('진행 중 스트림을 언마운트에서 끊는다 — 버려질 답변에 토큰을 더 태우지 않는다', async () => {
    let signal: AbortSignal | undefined;
    // 끝나지 않는 스트림 — 사용자가 답변 도중에 닫는 상황이다.
    streamMessage.mockImplementation((_id: string, _q: string, s: AbortSignal) => {
      signal = s;
      // abort 되면 풀리는 대기다. 영원히 pending 인 promise 를 쓰면 제너레이터가 워커에 남아
      // 다른 테스트 파일을 간헐적으로 흔든다(실측: 전체 실행 2회 중 1회 무관한 파일이 red).
      return (async function* () {
        await new Promise<void>((resolve) => s.addEventListener('abort', () => resolve(), { once: true }));
        yield '';
      })();
    });
    const { unmount } = render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('질문');
    await waitFor(() => expect(signal).toBeDefined());
    expect(signal!.aborted).toBe(false);

    unmount();

    expect(signal!.aborted).toBe(true);
  });

  it('403(소유권·임대 만료)은 Conversation을 초기화해 다음 질문이 새로 만들게 한다', async () => {
    // 스트림 시작 전 403(streamMessage 자체가 던지는 경로) — SSE error 이벤트가 아니다.
    // mockRejectedValueOnce 는 Promise 를 주는데 streamMessage 는 async generator 라
    // consumeSseStream 의 for-await 가 'rejected is not async iterable' TypeError 를 낸다(실측).
    // 그러면 isAiHttpError 가 false 가 되어 403 초기화 경로 자체가 검증되지 않는다 — 던지는
    // async generator 로 줘야 status 403 이 그대로 전달된다.
    async function* rejects(): AsyncGenerator<string> {
      throw { code: 'BOOTH_LEASE_EXPIRED', message: '임대가 만료되었습니다.', status: 403 };
    }
    streamMessage.mockImplementationOnce(() => rejects()).mockImplementationOnce(() => mockStreamSuccess());

    render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} />);
    ask('첫 질문');
    await screen.findByRole('alert');

    ask('두 번째 질문');
    await waitFor(() => expect(createConversation).toHaveBeenCalledTimes(2));
  });
});
