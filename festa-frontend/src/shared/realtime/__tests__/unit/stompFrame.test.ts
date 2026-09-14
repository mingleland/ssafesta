import { describe, expect, it } from 'vitest';
import { decodeFrame, encodeFrame } from '../../stompFrame';

describe('stompFrame', () => {
  it('왕복해도 같은 프레임이다', () => {
    const frame = {
      command: 'SEND',
      headers: { destination: '/app/world/chat', 'content-type': 'application/json' },
      body: '{"content":"안녕 🙂"}',
    };
    expect(decodeFrame(encodeFrame(frame))).toEqual(frame);
  });

  it('헤더 값의 콜론·개행을 이스케이프해 프레임 경계가 어긋나지 않는다', () => {
    const frame = { command: 'CONNECT', headers: { Authorization: 'Bearer a:b\nc' }, body: '' };
    const raw = encodeFrame(frame);
    expect(raw).toContain('\\c');
    expect(decodeFrame(raw)?.headers.Authorization).toBe('Bearer a:b\nc');
  });

  it('헤더 없는 프레임도 읽는다', () => {
    expect(decodeFrame('CONNECTED\n\n\u0000')).toEqual({ command: 'CONNECTED', headers: {}, body: '' });
  });
});
