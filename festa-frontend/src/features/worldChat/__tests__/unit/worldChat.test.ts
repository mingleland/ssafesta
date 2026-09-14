import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  CHAT_ERROR_MESSAGE,
  MAX_CHAT_CODE_POINTS,
  __resetWorldChatForTests,
  canUseWorldChat,
  countCodePoints,
  getWorldChatSnapshot,
  openWorldChat,
  resolveEnterAction,
  sendWorldChat,
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

  it('열려 있어도 입력창 밖이면 전송하지 않는다', () => {
    expect(resolveEnterAction({}, { open: true, inputFocused: false, member: true })).toBe('ignore');
  });
});
