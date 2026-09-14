// 월드 공용 텍스트 채팅 상태 — 광장 잡담 한 줄이 오가는 자리 (S15P21A604-706, GitLab #187).
//
// **저장이 없다.** 서버에 표가 없어 재접속하면 이전 대화가 사라진다. 그래서 스크롤백을 그리지
// 않고 메모리 버퍼도 최근 것만 남긴다.
//
// **입력 텍스트가 여기 있다.** 화면이 들고 있지 않은 이유는 Enter 판정자를 `WorldPage` 하나로
// 두기 때문이다 — 전송도 그 판정자가 부르므로 값이 화면 바깥에 있어야 한다.
import { useSyncExternalStore } from 'react';
import {
  WORLD_CHAT_ERRORS,
  WORLD_CHAT_SEND,
  WORLD_CHAT_TOPIC,
} from '../../../shared/realtime/destinations';
import { connectRealtime, sendRealtime, subscribeRealtime } from '../../../shared/realtime/realtimeClient';
import { getSessionSnapshot } from '../../auth/model/session';

/** 서버와 같은 기준으로 센다 — `String.length` 는 UTF-16 단위라 이모지 하나가 2자가 된다 */
export const MAX_CHAT_CODE_POINTS = 100;
export const CHAT_COOLDOWN_MS = 3_000;
/** 저장이 없어 스크롤백이 없다. 화면이 들고 있을 이유가 없는 만큼만 남긴다 */
export const MAX_BUFFERED_MESSAGES = 100;

export const WORLD_CHAT_INPUT_ID = 'world-chat-input';

export interface WorldChatMessage {
  senderUserId: number;
  nickname: string;
  content: string;
  sentAt: string;
}

export interface WorldChatState {
  open: boolean;
  draft: string;
  messages: readonly WorldChatMessage[];
  /** 마지막 거절 사유. 보내기 전에 막은 것과 서버가 돌려준 것을 같은 자리에 둔다 */
  notice: string | null;
  cooldownUntil: number;
}

export const CHAT_ERROR_MESSAGE: Record<string, string> = {
  VALIDATION_FAILED: '보낼 수 없는 내용입니다',
  CHAT_TOO_FAST: '조금 빠릅니다. 3초 뒤에 다시 보내 주세요',
  CHAT_UNAVAILABLE: '지금은 채팅을 보낼 수 없습니다. 잠시 뒤 다시 시도해 주세요',
  MEMBER_ONLY: '로그인한 회원만 채팅할 수 있습니다',
};

const EMPTY: WorldChatState = {
  open: false,
  draft: '',
  messages: [],
  notice: null,
  cooldownUntil: 0,
};

let state: WorldChatState = EMPTY;
const listeners = new Set<() => void>();

function set(next: Partial<WorldChatState>): void {
  state = { ...state, ...next };
  for (const listener of listeners) listener();
}

export function countCodePoints(text: string): number {
  return [...text].length;
}

/** 회원만 연결이 선다 — WS 토큰이 회원에게만 발급된다. 게이트를 두 곳에 두지 않는다 */
export function canUseWorldChat(): boolean {
  return getSessionSnapshot().kind === 'member';
}

export function getWorldChatSnapshot(): WorldChatState {
  return state;
}

export function subscribeWorldChat(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useWorldChat(): WorldChatState {
  return useSyncExternalStore(subscribeWorldChat, getWorldChatSnapshot, getWorldChatSnapshot);
}

export function setWorldChatDraft(draft: string): void {
  set({ draft });
}

export function openWorldChat(): void {
  if (!canUseWorldChat()) {
    set({ notice: CHAT_ERROR_MESSAGE.MEMBER_ONLY });
    return;
  }
  set({ open: true, notice: null });
}

export function closeWorldChat(): void {
  if (!state.open) return;
  set({ open: false, draft: '' });
}

/** 게스트가 비활성 버튼을 눌렀을 때 — 막지 않고 왜 못 쓰는지 알려 준다 */
export function noticeMemberOnly(): void {
  set({ notice: CHAT_ERROR_MESSAGE.MEMBER_ONLY });
}

export type ChatRejection = 'EMPTY' | 'TOO_LONG' | 'COOLDOWN';

export type EnterAction = 'ignore' | 'open' | 'send';

/**
 * Enter 하나를 무엇으로 읽을지 정한다 — 판정은 `WorldPage` 의 리스너 한 곳이 쓰지만, 규칙 자체는
 * 화면 없이 잠글 수 있도록 여기 둔다.
 *
 * **조합 중 Enter 를 먼저 거른다.** 한글을 확정하는 Enter 가 `isComposing: true` 로 들어오는데,
 * 이것을 거르지 않으면 "안녕" 을 확정하는 순간 그대로 전송된다. `keyCode 229` 는 `isComposing` 이
 * 오지 않는 경로의 같은 신호다.
 */
export function resolveEnterAction(
  event: { isComposing?: boolean; keyCode?: number; shiftKey?: boolean },
  context: { open: boolean; inputFocused: boolean; member: boolean },
): EnterAction {
  if (event.isComposing === true || event.keyCode === 229) return 'ignore';
  if (event.shiftKey === true) return 'ignore';
  if (context.open) return context.inputFocused ? 'send' : 'ignore';
  return context.member ? 'open' : 'ignore';
}

export function validateWorldChat(text: string, now = Date.now()): ChatRejection | null {
  if (text.trim() === '') return 'EMPTY';
  if (countCodePoints(text) > MAX_CHAT_CODE_POINTS) return 'TOO_LONG';
  if (now < state.cooldownUntil) return 'COOLDOWN';
  return null;
}

/**
 * 보낸다. 거절되면 `false` 를 돌려주고 사유를 화면에 남긴다.
 *
 * 길이·공백·도배는 **보내기 전에** 막는다. 서버도 같은 것을 보지만, 왕복 한 번을 줄이는 편이
 * 낫고 `CHAT_TOO_FAST` 는 애초에 버튼이 잠겨 있으면 거의 만나지 않는다.
 */
export function sendWorldChat(text: string, now = Date.now()): boolean {
  const rejection = validateWorldChat(text, now);
  if (rejection === 'EMPTY') {
    set({ notice: null });
    return false;
  }
  if (rejection === 'TOO_LONG') {
    set({ notice: MAX_CHAT_CODE_POINTS + '자까지 보낼 수 있습니다' });
    return false;
  }
  if (rejection === 'COOLDOWN') {
    set({ notice: CHAT_ERROR_MESSAGE.CHAT_TOO_FAST });
    return false;
  }

  // 보낼 것은 content 하나다. 닉네임·시각·보낸 사람은 서버가 읽지 않는다 — 클라이언트가 정할
  // 수 있는 값이면 사칭이 된다.
  sendRealtime(WORLD_CHAT_SEND, { content: text });
  set({ draft: '', notice: null, cooldownUntil: now + CHAT_COOLDOWN_MS });
  return true;
}

function pushMessage(raw: string): void {
  try {
    const message = JSON.parse(raw) as WorldChatMessage;
    const next = [...state.messages, message];
    set({ messages: next.slice(-MAX_BUFFERED_MESSAGES) });
  } catch {
    console.warn('[worldChat] 읽을 수 없는 메시지를 버렸다');
  }
}

function pushError(raw: string): void {
  try {
    const { code } = JSON.parse(raw) as { code: string; message: string };
    set({ notice: CHAT_ERROR_MESSAGE[code] ?? '채팅을 보내지 못했습니다' });
  } catch {
    set({ notice: '채팅을 보내지 못했습니다' });
  }
}

/**
 * 월드에 들어오면 구독을 건다 — 오버레이를 여는 시점이 아니다.
 *
 * 여는 시점에 걸면 구독 실패가 사용자 행동에 묶이고, 열기 전에 오간 말도 보이지 않는다.
 */
export function startWorldChat(): () => void {
  if (!canUseWorldChat()) return () => {};

  const stopMessages = subscribeRealtime(WORLD_CHAT_TOPIC, pushMessage);
  const stopErrors = subscribeRealtime(WORLD_CHAT_ERRORS, pushError);
  void connectRealtime().catch((error: unknown) => {
    console.error('[worldChat] 실시간 연결 실패 —', error);
  });

  return () => {
    stopMessages();
    stopErrors();
  };
}

// 테스트 전용
export function __resetWorldChatForTests(): void {
  state = EMPTY;
  listeners.clear();
}
