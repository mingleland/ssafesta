// 월드 채팅 레이어 — 버튼과 패널이 사는 자리 (S15P21A604-706, GitLab #187).
//
// **`.world-hud` 안에 두지 않는다.** HUD 는 `onMouseDown` 을 막아 캔버스 focus 를 지키는데
// (S15P21A604-648), 그 안에 입력창을 넣으면 클릭해도 focus 가 잡히지 않아 글자를 칠 수 없다.
// 그래서 `.world-scene` 의 형제 레이어로 둔다.
//
// Enter 는 여기서 듣지 않는다 — 판정자는 `WorldPage` 하나다.
import { useEffect, useLayoutEffect, useRef, useSyncExternalStore } from 'react';
import { getRealtimeStatus, subscribeRealtimeStatus } from '../../../shared/realtime/realtimeClient';
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

export function WorldChatLayer({ onHeightChange }: { onHeightChange?: (height: number) => void }) {
  const { open, draft, messages, notice } = useWorldChat();
  const member = canUseWorldChat();
  // 연결 상태는 transport 가 정본이다 — 화면은 읽기만 한다 (S15P21A604-790)
  const status = useSyncExternalStore(subscribeRealtimeStatus, getRealtimeStatus, getRealtimeStatus);
  const offline = status !== 'connected';
  const layerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const wasOpen = useRef(false);
  const shown = open ? messages : messages.slice(-VISIBLE_WHEN_CLOSED);

  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
    } else if (wasOpen.current) {
      // 닫으면 캔버스로 focus 를 돌려준다 — 안 그러면 WASD 가 죽은 채로 남는다
      document.querySelector('canvas')?.focus();
    }
    wasOpen.current = open;
  }, [open]);

  // 채팅은 .world-hud 의 형제라 CSS만으로 안내 카드의 실제 높이를 알 수 없다. 화면 소유자인
  // WorldPage 에 실제 높이 하나만 보고해 좌하단 스택을 맞춘다(S15P21A604-740).
  useLayoutEffect(() => {
    const layer = layerRef.current;
    if (layer === null || onHeightChange === undefined) return;
    const report = () => onHeightChange(Math.ceil(layer.getBoundingClientRect().height));
    report();
    if (typeof ResizeObserver === 'undefined') return () => onHeightChange(0);

    const observer = new ResizeObserver((entries) => {
      onHeightChange(Math.ceil(entries[0]?.contentRect.height ?? layer.getBoundingClientRect().height));
    });
    observer.observe(layer);
    return () => {
      observer.disconnect();
      onHeightChange(0);
    };
  }, [onHeightChange]);

  const over = countCodePoints(draft) > MAX_CHAT_CODE_POINTS;

  return (
    <div ref={layerRef} className={open ? 'world-chat world-chat-open' : 'world-chat'}>
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
        <>
          {/* 게스트는 연결 자체를 시도하지 않는다 — 그 상태를 끊김으로 알리면 거짓말이 된다 */}
          {member && offline && (
            <p className="world-chat-status" role="status">
              {status === 'reconnecting' ? '채팅 연결 중…' : '채팅 연결이 끊어졌습니다'}
            </p>
          )}
          <div className={offline ? 'world-chat-input-row world-chat-input-row-offline' : 'world-chat-input-row'}>
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
        </>
      )}
    </div>
  );
}
