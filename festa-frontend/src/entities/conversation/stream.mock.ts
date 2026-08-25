// spec 008 AI 대화 SSE Mock Stream Fixture — 계약 정합 자체가 검증 대상이라 SSE 와이어 텍스트
// 청크를 그대로 방출한다(파서 왕복 테스트가 곧 계약 테스트). 008 채팅 UI(김가현) 착수 전까지는
// 소비자가 없어 real api·api.select는 만들지 않는다 — entities/auth 패턴과 달리 이 모듈은
// 상태를 갖지 않는다(스트림은 요청마다 새로 열리고 재개하지 않음, #32 §3)
// 출처: Issue #32 최종 합의

import type { AiErrorCode, TimeoutPhase } from './stream.types';
import { DEFAULT_RETRYABLE } from './stream.types';

function frame(type: string, data: Record<string, unknown>): string {
  return `event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`;
}

interface MockStreamOptions {
  requestId?: string;
  conversationId?: string;
  messageId?: string;
  // true면 프레임을 문자 단위로 쪼개 방출한다 — 파서의 청크 경계 처리를 같은 fixture로 검증
  splitMid?: boolean;
}

function chunksOf(text: string, splitMid: boolean | undefined): string[] {
  return splitMid ? text.split('') : [text];
}

const DEFAULTS = {
  requestId: 'req_mock_01',
  conversationId: 'conv_mock_01',
  messageId: 'msg_mock_01',
};

// 정상 완료 시나리오: start → token×N → source → done, sequence 0부터 연속(#32 §3 정상 순서)
export async function* mockStreamSuccess(
  tokens: string[] = ['안녕하세요, ', '무엇을 도와드릴까요?'],
  options: MockStreamOptions = {},
): AsyncGenerator<string> {
  const { requestId, conversationId, messageId, splitMid } = { ...DEFAULTS, ...options };
  let sequence = 0;
  const env = { requestId, conversationId, messageId };

  yield* chunksOf(frame('start', { ...env, sequence: sequence++ }), splitMid);
  for (const delta of tokens) {
    yield* chunksOf(frame('token', { ...env, sequence: sequence++, delta }), splitMid);
  }
  yield* chunksOf(
    frame('source', {
      ...env,
      sequence: sequence++,
      documentId: 152,
      title: '프로젝트_기획서.pdf',
      chunkId: 'chunk_152_03',
    }),
    splitMid,
  );
  yield* chunksOf(
    frame('done', { ...env, sequence: sequence++, handoffRecommended: false }),
    splitMid,
  );
}

// 실패 시나리오: start → token → error (#32 §3 실패 순서, done과 상호 배타)
export async function* mockStreamError(
  code: AiErrorCode = 'LLM_TIMEOUT',
  options: MockStreamOptions & { timeoutPhase?: TimeoutPhase } = {},
): AsyncGenerator<string> {
  const { requestId, conversationId, messageId, splitMid, timeoutPhase } = {
    ...DEFAULTS,
    timeoutPhase: 'FIRST_TOKEN' as TimeoutPhase,
    ...options,
  };
  let sequence = 0;
  const env = { requestId, conversationId, messageId };

  yield* chunksOf(frame('start', { ...env, sequence: sequence++ }), splitMid);
  yield* chunksOf(frame('token', { ...env, sequence: sequence++, delta: '답변을 ' }), splitMid);
  yield* chunksOf(
    frame('error', {
      ...env,
      sequence: sequence++,
      code,
      message: `AI 응답 오류: ${code}`,
      retryable: DEFAULT_RETRYABLE[code],
      ...(code === 'LLM_TIMEOUT' ? { timeoutPhase } : {}),
    }),
    splitMid,
  );
}
