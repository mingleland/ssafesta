// 파싱된 AI SSE 이벤트를 화면이 바로 그릴 수 있는 누적 상태로 변환하고 sequence 무결성을 검사한다.
import { createSseParser } from './stream.parser';

export type SseConsumptionStatus = 'streaming' | 'done' | 'error' | 'truncated';

export interface SseConsumptionSnapshot {
  text: string;
  sources: string[];
  status: SseConsumptionStatus;
  errorMessage?: string;
  retryable?: boolean;
}

type SequenceWarning = (details: {
  requestId: string;
  expected: number;
  actual: number;
  terminal: boolean;
}) => void;

const TRUNCATED_MESSAGE = '응답이 중간에 끊겼습니다. 다시 시도해 주세요.';

export async function consumeSseStream(
  stream: AsyncIterable<string>,
  onUpdate: (snapshot: SseConsumptionSnapshot) => void,
  warn: SequenceWarning = (details) => console.warn('[AI SSE] sequence 불일치', details),
): Promise<SseConsumptionSnapshot> {
  const parser = createSseParser();
  let expectedSequence = 0;
  let text = '';
  let terminal = false;
  const sourceKeys = new Set<string>();
  const sources: string[] = [];

  const snapshot = (
    status: SseConsumptionStatus,
    errorMessage?: string,
    retryable?: boolean,
  ): SseConsumptionSnapshot => ({ text, sources: [...sources], status, errorMessage, retryable });

  const truncated = () => {
    terminal = true;
    const next = snapshot('truncated', TRUNCATED_MESSAGE, true);
    onUpdate(next);
    return next;
  };

  const apply = (events: ReturnType<ReturnType<typeof createSseParser>['push']>) => {
    for (const event of events) {
      if (terminal) break;

      const isTerminal = event.type === 'done' || event.type === 'error';
      if (event.sequence !== expectedSequence) {
        warn({
          requestId: event.requestId,
          expected: expectedSequence,
          actual: event.sequence,
          terminal: isTerminal,
        });
        if (isTerminal) return truncated();
      }
      expectedSequence = event.sequence + 1;

      if (event.type === 'token') {
        text += event.delta;
        onUpdate(snapshot('streaming'));
      } else if (event.type === 'source') {
        const key = `${event.documentId}:${event.chunkId}`;
        if (!sourceKeys.has(key)) {
          sourceKeys.add(key);
          sources.push(event.title);
        }
        onUpdate(snapshot('streaming'));
      } else if (event.type === 'done') {
        terminal = true;
        const next = snapshot('done');
        onUpdate(next);
        return next;
      } else if (event.type === 'error') {
        terminal = true;
        const next = snapshot('error', event.message, event.retryable);
        onUpdate(next);
        return next;
      }
    }
    return undefined;
  };

  for await (const chunk of stream) {
    const result = apply(parser.push(chunk));
    if (result !== undefined) return result;
  }

  const flushed = apply(parser.flush());
  if (flushed !== undefined) return flushed;
  return truncated();
}
