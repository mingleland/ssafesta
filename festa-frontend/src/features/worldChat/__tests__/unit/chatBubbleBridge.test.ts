// @vitest-environment jsdom
// 말풍선 송신부 (S15P21A604-851, GitLab #203) — 서버 payload 가 손대지 않고 Unity 로 가는지.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as sessionManager from '../../../../unity/host/sessionManager';
import { __resetWorldChatForTests, __receiveWorldChatRawForTests, getWorldChatSnapshot } from '../../model/worldChat';

let SendMessage: ReturnType<typeof vi.fn>;

function mountUnity(): void {
  SendMessage = vi.fn();
  vi.spyOn(sessionManager, 'getReadyUnityInstance').mockReturnValue({ SendMessage } as never);
}

beforeEach(() => {
  __resetWorldChatForTests();
  SendMessage = vi.fn();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('월드 채팅 말풍선 중계', () => {
  it('받은 문자열을 그대로 넘긴다 — 재직렬화하지 않아 계약 밖 필드가 살아 있다', () => {
    mountUnity();
    const raw = '{"senderUserId":12,"nickname":"정헌","content":"안녕하세요","sentAt":"2026-09-17T05:00:00Z","messageId":"srv-1"}';

    __receiveWorldChatRawForTests(raw);

    expect(SendMessage).toHaveBeenCalledTimes(1);
    expect(SendMessage).toHaveBeenCalledWith('WorldChatBridge', 'ReceiveChat', raw);
    // 화면 로그도 같은 사건을 받는다 — 둘 중 하나만 도는 상태를 만들지 않는다
    expect(getWorldChatSnapshot().messages).toHaveLength(1);
  });

  it('표시 시간을 실어 보내지 않는다 — 월드가 글자 수로 정한다', () => {
    mountUnity();
    __receiveWorldChatRawForTests('{"senderUserId":1,"nickname":"a","content":"hi","sentAt":"t"}');

    expect(String(SendMessage.mock.calls[0][2])).not.toContain('durationMs');
  });

  it('입장 알림(JOIN)은 보내지 않는다 — 말이 아니라 사건이라 띄울 자리가 없다', () => {
    mountUnity();
    __receiveWorldChatRawForTests('{"type":"JOIN","nickname":"정헌","sentAt":"t"}');

    expect(SendMessage).not.toHaveBeenCalled();
    expect(getWorldChatSnapshot().messages).toHaveLength(1);
  });

  it('읽을 수 없는 payload 는 중계하지 않는다', () => {
    mountUnity();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    __receiveWorldChatRawForTests('{ not json');

    expect(SendMessage).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalled();
  });

  it('월드가 안 떠 있으면 조용히 건너뛴다 — 화면 로그는 그대로 쌓인다', () => {
    vi.spyOn(sessionManager, 'getReadyUnityInstance').mockReturnValue(null);

    expect(() => __receiveWorldChatRawForTests('{"senderUserId":1,"nickname":"a","content":"hi","sentAt":"t"}')).not.toThrow();
    expect(getWorldChatSnapshot().messages).toHaveLength(1);
  });
});

