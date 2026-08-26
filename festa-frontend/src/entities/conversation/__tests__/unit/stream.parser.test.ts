// stream.parser.ts 계약 회귀 방어 — 정상 프레임·청크 경계·heartbeat·3계열 SseProtocolError.
import { describe, expect, it } from 'vitest';
import { createSseParser } from '../../stream.parser';
import { SseProtocolError } from '../../stream.types';

function frame(type: string, data: Record<string, unknown>): string {
  return `event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`;
}

const ENV = { requestId: 'r1', conversationId: 'c1', messageId: 'm1' };

describe('정상 프레임 파싱', () => {
  it('완성된 프레임을 push에서 바로 이벤트로 돌려준다', () => {
    const parser = createSseParser();
    const events = parser.push(frame('start', { ...ENV, sequence: 0 }));
    expect(events).toEqual([{ type: 'start', ...ENV, sequence: 0 }]);
  });

  it('한 청크에 여러 프레임이 있으면 전부 반환한다', () => {
    const parser = createSseParser();
    const chunk =
      frame('start', { ...ENV, sequence: 0 }) + frame('token', { ...ENV, sequence: 1, delta: 'a' });
    expect(parser.push(chunk)).toHaveLength(2);
  });

  it('comment(heartbeat) 라인은 무시하고 오류를 던지지 않는다', () => {
    const parser = createSseParser();
    const events = parser.push(': keep-alive\n\n');
    expect(events).toEqual([]);
  });
});

describe('청크 경계 분할', () => {
  it('프레임이 여러 청크로 쪼개져 도착해도 완성 시점에 이벤트를 낸다', () => {
    const parser = createSseParser();
    const full = frame('done', { ...ENV, sequence: 3, handoffRecommended: false });
    const mid = Math.floor(full.length / 2);

    expect(parser.push(full.slice(0, mid))).toEqual([]);
    expect(parser.push(full.slice(mid))).toEqual([
      { type: 'done', ...ENV, sequence: 3, handoffRecommended: false },
    ]);
  });

  it('flush()는 끝에 빈 줄이 없는 마지막 프레임을 처리한다', () => {
    const parser = createSseParser();
    const noTrailingBlank = `event: start\ndata: ${JSON.stringify({ type: 'start', ...ENV, sequence: 0 })}`;
    expect(parser.push(noTrailingBlank)).toEqual([]);
    expect(parser.flush()).toEqual([{ type: 'start', ...ENV, sequence: 0 }]);
  });

  it('flush()는 빈 버퍼에 대해 빈 배열을 돌려준다', () => {
    const parser = createSseParser();
    expect(parser.flush()).toEqual([]);
  });
});

describe('SseProtocolError — 3계열', () => {
  it('event:와 data.type이 다르면 EVENT_TYPE_MISMATCH', () => {
    const parser = createSseParser();
    const bad = `event: start\ndata: ${JSON.stringify({ type: 'token', ...ENV, sequence: 0 })}\n\n`;
    expect(() => parser.push(bad)).toThrow(SseProtocolError);
    try {
      parser.push(bad);
    } catch (e) {
      expect((e as SseProtocolError).kind).toBe('EVENT_TYPE_MISMATCH');
    }
  });

  it('계약 5종 밖의 event 이름은 UNKNOWN_EVENT', () => {
    const parser = createSseParser();
    const bad = 'event: ping\ndata: {}\n\n';
    expect(() => parser.push(bad)).toThrow(SseProtocolError);
    try {
      parser.push(bad);
    } catch (e) {
      expect((e as SseProtocolError).kind).toBe('UNKNOWN_EVENT');
    }
  });

  it('data가 JSON으로 파싱되지 않으면 MALFORMED_DATA', () => {
    const parser = createSseParser();
    const bad = 'event: start\ndata: {not json\n\n';
    expect(() => parser.push(bad)).toThrow(SseProtocolError);
    try {
      parser.push(bad);
    } catch (e) {
      expect((e as SseProtocolError).kind).toBe('MALFORMED_DATA');
    }
  });
});
