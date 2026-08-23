// SSE 와이어 텍스트 → SseStreamEvent 변환 — 순수 함수, DOM/EventSource 의존 없음(vitest node env에서 검증 가능)
// EventSource는 GET만 지원하지만 계약 endpoint는 POST라 쓸 수 없다 — fetch 스트림을 직접 파싱해야 한다
// 출처: Issue #32 §4(event/data.type 정합), SSE 표준(comment 라인은 keep-alive, 오류 아님)

import type { SseStreamEvent } from './stream.types';
import { SseProtocolError } from './stream.types';

const KNOWN_EVENT_TYPES = new Set<SseStreamEvent['type']>([
  'start',
  'token',
  'source',
  'done',
  'error',
]);

// 청크 경계가 프레임 중간에 걸려도 안전하도록 내부에 미완성 버퍼를 들고 있는다
export function createSseParser() {
  let buffer = '';

  function parseFrame(frame: string): SseStreamEvent[] {
    const lines = frame.split('\n');
    let eventName: string | null = null;
    const dataLines: string[] = [];

    for (const line of lines) {
      if (line === '' || line.startsWith(':')) continue; // 빈 줄·comment(heartbeat)는 무시
      if (line.startsWith('event:')) {
        eventName = line.slice('event:'.length).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trim());
      }
      // 그 외 필드(id:, retry: 등)는 이 계약에서 쓰지 않으므로 무시
    }

    if (eventName === null && dataLines.length === 0) return []; // 내용 없는 프레임(순수 heartbeat)

    if (eventName === null) {
      throw new SseProtocolError('MALFORMED_DATA', 'SSE 프레임에 event: 라인이 없다', frame);
    }
    if (!KNOWN_EVENT_TYPES.has(eventName as SseStreamEvent['type'])) {
      throw new SseProtocolError(
        'UNKNOWN_EVENT',
        `계약에 없는 이벤트 이름: ${eventName}`,
        frame,
      );
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(dataLines.join('\n'));
    } catch {
      throw new SseProtocolError('MALFORMED_DATA', 'data가 JSON으로 파싱되지 않는다', frame);
    }

    if (
      typeof parsed !== 'object' ||
      parsed === null ||
      (parsed as { type?: unknown }).type !== eventName
    ) {
      throw new SseProtocolError(
        'EVENT_TYPE_MISMATCH',
        `event: ${eventName} 과 data.type이 일치하지 않는다`,
        frame,
      );
    }

    return [parsed as SseStreamEvent];
  }

  return {
    // 새 청크를 밀어 넣고, 완성된 프레임(빈 줄로 구분)이 있으면 이벤트로 반환한다
    push(chunk: string): SseStreamEvent[] {
      buffer += chunk;
      const events: SseStreamEvent[] = [];
      let boundary: number;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        events.push(...parseFrame(frame));
      }
      return events;
    },
    // 스트림 종료 시 버퍼에 남은 마지막 프레임(끝에 빈 줄이 없을 수 있음)을 처리한다
    flush(): SseStreamEvent[] {
      const remaining = buffer;
      buffer = '';
      if (remaining.trim() === '') return [];
      return parseFrame(remaining);
    },
  };
}
