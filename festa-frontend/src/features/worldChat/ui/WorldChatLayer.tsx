// 월드 채팅 레이어 — 버튼과 패널이 사는 자리 (S15P21A604-706, GitLab #187).
//
// **`.world-hud` 안에 두지 않는다.** HUD 는 `onMouseDown` 을 막아 캔버스 focus 를 지키는데
// (S15P21A604-648), 그 안에 입력창을 넣으면 클릭해도 focus 가 잡히지 않아 글자를 칠 수 없다.
// 그래서 `.world-scene` 의 형제 레이어로 둔다.
//
// Enter 는 여기서 듣지 않는다 — 판정자는 `WorldPage` 하나다.
import { useEffect, useRef } from 'react';
import {
  MAX_CHAT_CODE_POINTS,
  WORLD_CHAT_INPUT_ID,
  canUseWorldChat,
  countCodePoints,
  noticeMemberOnly,
  openWorldChat,
  setWorldChatDraft,
  useWorldChat,
} from '../model/worldChat';
import './worldChat.css';

const VISIBLE_WHEN_CLOSED = 4;

export function WorldChatLayer() {
  const { open, draft, messages, notice } = useWorldChat();
  const member = canUseWorldChat();
  const inputRef = useRef<HTMLInputElement>(null);
  const wasOpen = useRef(false);

  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
    } else if (wasOpen.current) {
      // 닫으면 캔버스로 focus 를 돌려준다 — 안 그러면 WASD 가 죽은 채로 남는다
      document.querySelector('canvas')?.focus();
    }
    wasOpen.current = open;
  }, [open]);

  const shown = open ? messages : messages.slice(-VISIBLE_WHEN_CLOSED);
  const over = countCodePoints(draft) > MAX_CHAT_CODE_POINTS;

  return (
    <div className={open ? 'world-chat world-chat-open' : 'world-chat'}>
      <button
        type="button"
        className="world-chat-launcher"
        // 게스트에게도 버튼은 보인다. 실제 disabled 로 만들면 클릭이 오지 않아 왜 못 쓰는지
        // 알려 줄 자리가 없다 — 표현만 비활성으로 두고 클릭은 받는다.
        aria-disabled={!member}
        onClick={() => (member ? openWorldChat() : noticeMemberOnly())}
      >
        채팅
      </button>

      <ul className="world-chat-log" aria-label="월드 채팅">
        {shown.map((message) => (
          <li key={message.sentAt + String(message.senderUserId)}>
            <b>{message.nickname}</b> {message.content}
          </li>
        ))}
      </ul>

      {notice !== null && <p className="world-chat-notice">{notice}</p>}

      {open && (
        <div className="world-chat-input-row">
          <input
            id={WORLD_CHAT_INPUT_ID}
            ref={inputRef}
            className={over ? 'world-chat-input world-chat-input-over' : 'world-chat-input'}
            value={draft}
            onChange={(event) => setWorldChatDraft(event.target.value)}
            placeholder="Enter 로 보내고 ESC 로 닫습니다"
            aria-label="채팅 입력"
            autoComplete="off"
          />
          <span className="world-chat-count">
            {countCodePoints(draft)}/{MAX_CHAT_CODE_POINTS}
          </span>
        </div>
      )}
    </div>
  );
}
