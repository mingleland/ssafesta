// stream.mock.ts fixture 왕복 검증 — fixture가 낸 SSE 텍스트를 parser로 되돌려 계약 순서를 확인한다.
import { describe, expect, it } from 'vitest';
import { createSseParser } from '../../stream.parser';
import { mockStreamError, mockStreamSuccess } from '../../stream.mock';
import type { SseStreamEvent } from '../../stream.types';

async function collect(gen: AsyncGenerator<string>): Promise<SseStreamEvent[]> {
  const parser = createSseParser();
  const events: SseStreamEvent[] = [];
  for await (const chunk of gen) {
    events.push(...parser.push(chunk));
  }
  events.push(...parser.flush());
  return events;
}

describe('mockStreamSuccess', () => {
  it('start 선행 · source→done 직전 · 종료는 done 하나', async () => {
    const events = await collect(mockStreamSuccess());
    expect(events[0].type).toBe('start');
    expect(events.at(-1)?.type).toBe('done');
    expect(events.filter((e) => e.type === 'done')).toHaveLength(1);
    expect(events.some((e) => e.type === 'error')).toBe(false);
  });

  it('sequence가 0부터 이벤트마다 1씩 연속 증가한다', async () => {
    const events = await collect(mockStreamSuccess());
    events.forEach((e, i) => expect(e.sequence).toBe(i));
  });

  it('splitMid로 프레임이 쪼개져 도착해도 파서가 동일하게 복원한다', async () => {
    const whole = await collect(mockStreamSuccess());
    const split = await collect(mockStreamSuccess(undefined, { splitMid: true }));
    expect(split).toEqual(whole);
  });
});

describe('mockStreamError', () => {
  it('start → token → error 순서이고 done은 없다(상호 배타)', async () => {
    const events = await collect(mockStreamError());
    expect(events.map((e) => e.type)).toEqual(['start', 'token', 'error']);
  });

  it('기본값은 LLM_TIMEOUT + FIRST_TOKEN, retryable은 DEFAULT_RETRYABLE과 일치', async () => {
    const events = await collect(mockStreamError());
    const error = events.at(-1);
    expect(error).toMatchObject({ type: 'error', code: 'LLM_TIMEOUT', timeoutPhase: 'FIRST_TOKEN', retryable: true });
  });

  it('code를 지정하면 해당 코드와 DEFAULT_RETRYABLE 값으로 방출한다', async () => {
    const events = await collect(mockStreamError('INVALID_REQUEST'));
    const error = events.at(-1);
    expect(error).toMatchObject({ type: 'error', code: 'INVALID_REQUEST', retryable: false });
    expect(error).not.toHaveProperty('timeoutPhase');
  });
});
