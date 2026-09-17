// 월드 채팅 레이어 — 버튼과 패널이 사는 자리 (S15P21A604-706, GitLab #187).
//
// **`.world-hud` 안에 두지 않는다.** HUD 는 `onMouseDown` 을 막아 캔버스 focus 를 지키는데
// (S15P21A604-648), 그 안에 입력창을 넣으면 클릭해도 focus 가 잡히지 않아 글자를 칠 수 없다.
// 그래서 `.world-scene` 의 형제 레이어로 둔다.
//
// Enter 는 여기서 듣지 않는다 — 판정자는 `WorldPage` 하나다.
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
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
import type { ReceivedChatMessage, WorldChatJoinNotice } from '../model/worldChat';
import './worldChat.css';

const VISIBLE_WHEN_CLOSED = 4;
/** 평상시엔 숫자를 띄우지 않는다. 상한이 가까워질 때만 보인다 (S15P21A604-791) */
const COUNTER_FROM = 80;
/** 이만큼 안쪽이면 "맨 아래를 보고 있다" 로 읽는다 */
const BOTTOM_SLACK_PX = 24;

function isAtBottom(log: HTMLElement): boolean {
  return log.scrollHeight - log.scrollTop - log.clientHeight <= BOTTOM_SLACK_PX;
}

function isJoinNotice(message: ReceivedChatMessage): message is WorldChatJoinNotice & { seq: number } {
  return 'type' in message && message.type === 'JOIN';
}

type SpokenMessage = Exclude<ReceivedChatMessage, WorldChatJoinNotice & { seq: number }>;
function isSpoken(message: ReceivedChatMessage): message is SpokenMessage {
  return !isJoinNotice(message);
}

export function WorldChatLayer({ onHeightChange }: { onHeightChange?: (height: number) => void }) {
  const { open, draft, messages, notice } = useWorldChat();
  const member = canUseWorldChat();
  // 연결 상태는 transport 가 정본이다 — 화면은 읽기만 한다 (S15P21A604-790)
  const status = useSyncExternalStore(subscribeRealtimeStatus, getRealtimeStatus, getRealtimeStatus);
  const offline = status !== 'connected';
  const layerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const logRef = useRef<HTMLUListElement>(null);
  const atBottomRef = useRef(true);
  const lastSeqRef = useRef(0);
  const [unread, setUnread] = useState(0);
  const wasOpen = useRef(false);
  // 입장 알림(JOIN)은 STOMP 연결 사건이라 재연결·같은 계정의 다른 창에서도 온다. 닫힌 상태의 옅은 줄과
  // 읽기 알림은 **말**만 다룬다 — 안 그러면 막 들어온 사람의 첫 화면이 남의 연결 알림이다 (S15P21A604-855).
  // 열었을 때는 그대로 보여 준다: 그때는 사건도 읽을 자리가 있다.
  const spoken = messages.filter(isSpoken);
  const shown = open ? messages : spoken.slice(-VISIBLE_WHEN_CLOSED);
  const latest = spoken[spoken.length - 1];
  const latestAnnouncement = latest === undefined
    ? ''
    : latest.nickname + ': ' + latest.content;

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

  // 새 메시지를 어디로 보낼지는 **사용자가 지금 무엇을 보고 있는가**로 갈린다. 맨 아래를 보고
  // 있으면 따라 내려가고, 위를 읽는 중이면 그 자리를 지키고 몇 개가 왔는지만 알린다 —
  // 읽던 줄을 빼앗기지 않게 (S15P21A604-791).
  useEffect(() => {
    const latestSeq = latest?.seq ?? 0;
    const arrived = latestSeq > lastSeqRef.current;
    lastSeqRef.current = latestSeq;

    const log = logRef.current;
    if (!open || log === null) {
      // Passive 로 돌아가면 미확인 수를 들고 있지 않는다 — 읽을 자리가 없다
      setUnread(0);
      return;
    }
    if (atBottomRef.current) {
      log.scrollTop = log.scrollHeight;
      setUnread(0);
      return;
    }
    if (arrived) setUnread((n) => n + 1);
  }, [latest, open]);

  function jumpToLatest(): void {
    const log = logRef.current;
    if (log !== null) log.scrollTop = log.scrollHeight;
    atBottomRef.current = true;
    setUnread(0);
  }

  return (
    <div ref={layerRef} className={open ? 'world-chat world-chat-open' : 'world-chat'}>
      {/* 로그 전체에 aria-live 를 걸면 새 줄 하나마다 전부가 다시 읽힌다 — 새로 온 것만 따로 알린다 */}
      <p className="world-chat-sr" aria-live="polite">
        {latestAnnouncement}
      </p>

      {/* 받은 말이 없으면 상자를 그리지 않는다. 게스트에게 빈 상자만 남던 자리이고, 회원도 첫 말이
          오기 전까지는 월드를 가릴 이유가 없다. 게스트 구독 정책(A·B)과 무관하게 성립한다. */}
      {shown.length > 0 && (
        <ul
          ref={logRef}
          className="world-chat-log"
          aria-label="월드 채팅"
          onScroll={(event) => {
            atBottomRef.current = isAtBottom(event.currentTarget);
            if (atBottomRef.current) setUnread(0);
          }}
        >
          {shown.map((message) => isJoinNotice(message) ? (
            <li key={message.seq} className="world-chat-join" role="status">
              {message.nickname}님이 입장하셨습니다.
            </li>
          ) : (
            <li key={message.seq}>
              <b>{message.nickname}</b> {message.content}
            </li>
          ))}
        </ul>
      )}

      {/* 저장이 없다는 사실은 한 번만, 그것도 열었을 때만 말한다 (S15P21A604-791) */}
      {open && shown.length === 0 && <p className="world-chat-hint">월드 채팅은 접속 중인 동안만 표시됩니다</p>}

      {open && unread > 0 && (
        <button type="button" className="world-chat-unread" onClick={jumpToLatest}>
          새 메시지 {unread}개 ↓
        </button>
      )}

      {/* 보내지 못한 이유는 즉시 알아야 한다 — 로그와 달리 assertive 다 */}
      {notice !== null && (
        <p className="world-chat-notice" role="alert">
          {notice}
        </p>
      )}

      {/* 게스트는 연결 자체를 시도하지 않는다 — 그 상태를 끊김으로 알리면 거짓말이 된다 */}
      {open && member && offline && (
        <p className="world-chat-status" role="status">
          {status === 'reconnecting' ? '채팅 연결 중…' : '채팅 연결이 끊어졌습니다'}
        </p>
      )}

      {/* 입력창은 **항상 떠 있다** (2026-09-16). 예전에는 '채팅' 버튼을 눌러야 나타났는데, 버튼과
          입력창이 같은 자리를 번갈아 쓰면서 쓸 수 있는지조차 한눈에 안 보였다. 지금은 늘 보이고,
          거기 focus 가 있는 동안만 Active 다 — focus 가 곧 키보드 주인이라(`captureAllKeyboardInput=false`)
          그 사실이 그대로 WASD 라우팅과 맞는다. */}
      <div className={offline ? 'world-chat-input-row world-chat-input-row-offline' : 'world-chat-input-row'}>
        <input
          id={WORLD_CHAT_INPUT_ID}
          ref={inputRef}
          className={over ? 'world-chat-input world-chat-input-over' : 'world-chat-input'}
          value={draft}
          // 게스트는 칸을 보되 쓰지는 못한다. disabled 로 두면 focus 가 오지 않아 왜 못 쓰는지
          // 알려 줄 자리가 없다 — readOnly 로 두고 이유를 말한다.
          readOnly={!member}
          onFocus={() => (member ? openWorldChat() : noticeMemberOnly())}
          onChange={(event) => setWorldChatDraft(event.target.value)}
          placeholder={member ? 'Enter 로 보냅니다' : '로그인하면 채팅할 수 있습니다'}
          aria-label="채팅 입력"
          autoComplete="off"
        />
        {countCodePoints(draft) >= COUNTER_FROM && (
          <span className="world-chat-count">
            {countCodePoints(draft)}/{MAX_CHAT_CODE_POINTS}
          </span>
        )}
      </div>
    </div>
  );
}
