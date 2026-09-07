// @vitest-environment jsdom
// AI Chat overlay가 정상·오류·sequence 결번 mock stream을 사용자 상태로 렌더하는지 검증한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { mockStreamError, mockStreamSequenceGap, mockStreamSuccess } from '../../../../../entities/conversation/stream.mock';
import { AiChatOverlay } from '../../AiChatOverlay';

HTMLElement.prototype.scrollTo = vi.fn();

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function askFirstSuggestion(streamFactory: (tokens: string[]) => AsyncIterable<string>) {
  render(<AiChatOverlay payload={{ boothId: 7, agentId: 3 }} streamFactory={streamFactory} />);
  fireEvent.click(screen.getByRole('button', { name: '어떤 프로젝트를 전시하나요?' }));
}

describe('AiChatOverlay SSE 렌더링', () => {
  it('token 누적 답변과 source 문서명을 done 뒤 표시한다', async () => {
    askFirstSuggestion((tokens) => mockStreamSuccess(tokens));

    expect(await screen.findByText(/부스에 등록된 자료를 찾아봤어요/)).toBeTruthy();
    expect(await screen.findByText('프로젝트_기획서.pdf')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('error envelope의 메시지와 재시도 UX를 표시한다', async () => {
    const factory = vi.fn(() => mockStreamError());
    askFirstSuggestion(factory);

    expect((await screen.findByRole('alert')).textContent).toContain('AI 응답 오류: LLM_TIMEOUT');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(factory).toHaveBeenCalledTimes(2));
  });

  it('종료 sequence 결번을 잘린 응답으로 안내한다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    askFirstSuggestion(() => mockStreamSequenceGap());

    expect((await screen.findByRole('alert')).textContent).toContain('응답이 중간에 끊겼습니다');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });
});
