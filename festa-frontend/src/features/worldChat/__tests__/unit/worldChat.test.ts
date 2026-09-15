import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  CHAT_ERROR_MESSAGE,
  MAX_CHAT_CODE_POINTS,
  NOTICE_TTL_MS,
  __pushWorldChatForTests,
  __resetWorldChatForTests,
  canUseWorldChat,
  closeWorldChat,
  countCodePoints,
  getWorldChatSnapshot,
  noticeMemberOnly,
  openWorldChat,
  resolveEnterAction,
  sendWorldChat,
  setWorldChatDraft,
  validateWorldChat,
} from '../../model/worldChat';
import * as realtime from '../../../../shared/realtime/realtimeClient';

const FUTURE = '2026-12-31T00:00:00.000Z';
let sent: unknown[];

beforeEach(() => {
  __resetWorldChatForTests();
  __resetSessionForTests();
  sent = [];
  vi.spyOn(realtime, 'sendRealtime').mockImplementation((_d, body) => {
    sent.push(body);
  });
  setMemberSession('at', FUTURE);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('worldChat 길이·도배', () => {
  it('code point 로 센다 — 이모지 100개는 통과하고 101개는 막힌다', () => {
    const emoji100 = '🙂'.repeat(100);
    expect(countCodePoints(emoji100)).toBe(100);
    expect(emoji100.length).toBe(200);

    expect(validateWorldChat('a'.repeat(99))).toBeNull();
    expect(validateWorldChat(emoji100)).toBeNull();
    expect(validateWorldChat('🙂'.repeat(101))).toBe('TOO_LONG');
  });

  it('공백만이면 보내지 않는다', () => {
    expect(sendWorldChat('   ')).toBe(false);
    expect(sent).toHaveLength(0);
  });

  it('상한을 넘으면 보내지 않고 안내를 남긴다', () => {
    expect(sendWorldChat('가'.repeat(MAX_CHAT_CODE_POINTS + 1))).toBe(false);
    expect(sent).toHaveLength(0);
    expect(getWorldChatSnapshot().notice).toContain(String(MAX_CHAT_CODE_POINTS));
  });

  it('보낸 뒤 3초 동안 다시 보내지 않는다', () => {
    expect(sendWorldChat('안녕하세요', 1_000)).toBe(true);
    expect(sendWorldChat('또 보냅니다', 2_000)).toBe(false);
    expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.CHAT_TOO_FAST);
    expect(sendWorldChat('이제 됩니다', 4_100)).toBe(true);
    expect(sent).toHaveLength(2);
  });

  it('보낼 것은 content 하나다 — 닉네임·시각을 싣지 않는다', () => {
    sendWorldChat('안녕', 0);
    expect(sent[0]).toEqual({ content: '안녕' });
  });
});

describe('worldChat 게스트', () => {
  it('게스트는 열지 못하고 회원 전용 안내를 받는다', () => {
    __resetSessionForTests();
    setGuestSession('at', FUTURE);

    expect(canUseWorldChat()).toBe(false);
    openWorldChat();
    expect(getWorldChatSnapshot().open).toBe(false);
    expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.MEMBER_ONLY);
  });
});

describe('Enter 판정', () => {
  const closedMember = { open: false, inputFocused: false, member: true };
  const openFocused = { open: true, inputFocused: true, member: true };

  it('월드에서 누른 Enter 는 채팅을 연다', () => {
    expect(resolveEnterAction({}, closedMember)).toBe('open');
  });

  it('입력창에서 누른 Enter 는 보낸다', () => {
    expect(resolveEnterAction({}, openFocused)).toBe('send');
  });

  it('한글 조합 중 Enter 는 토글도 전송도 하지 않는다', () => {
    expect(resolveEnterAction({ isComposing: true }, openFocused)).toBe('ignore');
    expect(resolveEnterAction({ isComposing: true }, closedMember)).toBe('ignore');
  });

  it('isComposing 이 오지 않는 경로의 keyCode 229 도 같게 읽는다', () => {
    expect(resolveEnterAction({ keyCode: 229 }, openFocused)).toBe('ignore');
    expect(resolveEnterAction({ keyCode: 229 }, closedMember)).toBe('ignore');
  });

  it('Shift+Enter 는 아무것도 하지 않는다 — 줄바꿈을 만들지 않는다', () => {
    expect(resolveEnterAction({ shiftKey: true }, openFocused)).toBe('ignore');
  });

  it('게스트의 Enter 는 채팅을 열지 않는다', () => {
    expect(resolveEnterAction({}, { open: false, inputFocused: false, member: false })).toBe('ignore');
  });

  // S15P21A604-791 — 패널은 떠 있는데 입력창이 focus 를 잃은 상태를 남기지 않는다.
  it('열려 있는데 입력창 밖이면 전송하지 않고 그 입력창으로 돌아간다', () => {
    expect(resolveEnterAction({}, { open: true, inputFocused: false, member: true })).toBe('focus');
  });
});

// S15P21A604-790 — 예외가 window 까지 올라가고 화면에는 아무것도 남지 않던 자리.
describe('전송 실패 (S15P21A604-790)', () => {
  it('transport 가 던져도 밖으로 새지 않고, 안내를 남기며 입력값을 지키고, 쿨다운을 걸지 않는다', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const send = vi.spyOn(realtime, 'sendRealtime').mockImplementation(() => {
      throw new Error('[realtime] 연결되지 않았다');
    });
    setWorldChatDraft('연결이 끊긴 동안 쓴 말');

    let result: boolean | undefined;
    expect(() => {
      result = sendWorldChat('연결이 끊긴 동안 쓴 말', 1_000);
    }).not.toThrow();

    expect(result).toBe(false);
    expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.CHAT_UNAVAILABLE);
    // 입력값은 살아 있어야 한다 — 연결이 돌아오면 그대로 다시 보낸다
    expect(getWorldChatSnapshot().draft).toBe('연결이 끊긴 동안 쓴 말');
    // 보내지 못한 것에 도배 제한을 걸지 않는다
    expect(getWorldChatSnapshot().cooldownUntil).toBe(0);
    // 자동 재전송 금지 — 서버 도달 여부를 알 수 없어 중복이 된다
    expect(send).toHaveBeenCalledTimes(1);
    expect(consoleError).toHaveBeenCalled();
  });

  it('연결이 돌아오면 사용자가 같은 말을 손으로 다시 보낼 수 있다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const send = vi.spyOn(realtime, 'sendRealtime').mockImplementation(() => {
      throw new Error('[realtime] 연결되지 않았다');
    });
    expect(sendWorldChat('다시 보낼 말', 1_000)).toBe(false);

    send.mockImplementation((_d, body) => {
      sent.push(body);
    });
    expect(sendWorldChat('다시 보낼 말', 2_000)).toBe(true);
    expect(sent).toEqual([{ content: '다시 보낼 말' }]);
  });
});

// S15P21A604-791 — 안내가 화면에 눌러앉던 자리. 게스트는 지우는 경로(성공)를 밟을 수 없었다.
describe('안내 수명 (S15P21A604-791)', () => {
  it('안내는 스스로 사라진다', () => {
    vi.useFakeTimers();
    noticeMemberOnly();
    expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.MEMBER_ONLY);

    vi.advanceTimersByTime(NOTICE_TTL_MS - 1);
    expect(getWorldChatSnapshot().notice).toBe(CHAT_ERROR_MESSAGE.MEMBER_ONLY);

    vi.advanceTimersByTime(1);
    expect(getWorldChatSnapshot().notice).toBeNull();
    vi.useRealTimers();
  });

  it('패널을 닫으면 남아 있던 안내도 함께 걷는다', () => {
    openWorldChat();
    sendWorldChat('가'.repeat(MAX_CHAT_CODE_POINTS + 1));
    expect(getWorldChatSnapshot().notice).not.toBeNull();

    closeWorldChat();
    expect(getWorldChatSnapshot().notice).toBeNull();
  });
});

describe('메시지 식별자 (S15P21A604-791)', () => {
  it('같은 사람이 같은 시각에 보낸 두 줄도 서로 다른 key 를 받는다', () => {
    const same = { senderUserId: 7, nickname: '정헌', sentAt: '2026-09-15T05:00:00.000Z' };
    __pushWorldChatForTests([
      { ...same, content: '첫 줄' },
      { ...same, content: '둘째 줄' },
    ]);

    const seqs = getWorldChatSnapshot().messages.map((m) => m.seq);
    expect(seqs).toEqual([1, 2]);
    expect(new Set(seqs).size).toBe(2);
  });
});
