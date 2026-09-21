// 월드 채팅 레이어 — 로그와 입력칸이 사는 자리 (S15P21A604-706, GitLab #187).
//
// **화면은 두 모습뿐이다.**
//
//   Passive(닫힘)  최근 말 몇 줄만 월드 위에 얹힌 게임 HUD. 패널도 입력칸도 없다.
//   Active(열림)   같은 좌하단 자리에서 펴지는 compact panel —
//                  Message Area / Status Area / Composer 세 층으로 읽힌다.
//
// 이 파일이 가진 것은 그 **표현**뿐이다. 무엇을 보여 줄지(messages·notice·open)는 모델이,
// 언제 열고 닫을지는 `WorldPage` 의 Enter 판정자가 정한다 — 여기서 상태를 만들지 않는다.
//
// **`.world-hud` 안에 두지 않는다.** HUD 는 `onMouseDown` 을 막아 캔버스 focus 를 지키는데
// (S15P21A604-648), 그 안에 입력창을 넣으면 클릭해도 focus 가 잡히지 않아 글자를 칠 수 없다.
// 그래서 `.world-scene` 의 형제 레이어로 둔다.
//
// Enter 는 여기서 듣지 않는다 — 판정자는 `WorldPage` 하나다.
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';
import { getRealtimeStatus, subscribeRealtimeStatus } from '../../../shared/realtime/realtimeClient';
import {
  CHAT_ERROR_MESSAGE,
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

/**
 * 안내 한 문장을 **두 덩어리**로 가른다 — 앞이 '무슨 일인지', 뒤가 '그래서 어떻게' 다.
 *
 * 한 줄로 늘어놓으면 알림이 옆으로 길어져 토스트가 아니라 띠가 되고, 폭을 좁히면 줄이 우연히
 * 깨진 것처럼 보인다. 의도한 2행이면 둘 다 아니다.
 *
 * 가를 자리(마침표 뒤 공백)가 없으면 한 덩어리로 둔다 — 문장을 억지로 쪼개지 않는다.
 */
function splitNotice(text: string): { head: string; tail: string | null } {
  const at = text.indexOf('. ');
  return at === -1
    ? { head: text, tail: null }
    : { head: text.slice(0, at), tail: text.slice(at + 2) };
}

export function WorldChatLayer({ onHeightChange }: { onHeightChange?: (height: number) => void }) {
  const { open, draft, messages, notice, cooldownUntil } = useWorldChat();
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
  // **로그는 말만 싣는다.** 입장 알림(JOIN)은 STOMP 연결 사건이라 재연결·같은 계정의 다른 창에서도
  // 오고(S15P21A604-855), 한동안 열었을 때만 로그에 섞어 보여 줬다. 지금은 열려 있든 아니든
  // 시스템 안내 한 줄이 맡는다 — 같은 사건이 화면 상태에 따라 다른 모양으로 나오지 않는다.
  const spoken = messages.filter(isSpoken);
  const shown = open ? spoken : spoken.slice(-VISIBLE_WHEN_CLOSED);
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

  // ── 한 줄 자리에 무엇을 적는가 ──────────────────────────────────────────
  //
  // Passive 의 일시 안내와 Active 의 Status Area 는 같은 우선순위를 쓴다. 두 곳에서 따로 정하면
  // 같은 사건이 화면 상태에 따라 다르게 읽힌다.
  //
  //   거절 사유(notice) > 연결 이상 > 방금 들어온 사람
  //
  // 정상 연결은 아무 말도 하지 않는다 — 평상시에 군더더기를 두지 않는다.

  // **rate-limit 안내만 수명이 다르다.**
  //
  // 모델의 `notice` 는 6초를 살고 스스로 걷힌다(NOTICE_TTL_MS). 그 값이 화면의 수명까지
  // 정하면 10초·30초 벌칙은 안내가 먼저 사라져, 사용자는 남은 대기를 알 길 없이 빈 화면에
  // 대고 Enter 를 누르게 된다. 그래서 **화면 쪽에서만** 벌칙이 끝나는 시각을 붙잡아 둔다 —
  // 모델의 TTL 도 rate-limit 정책도 그대로다.
  //
  // 붙잡는 조건이 `cooldownUntil > now` 가 **아니라** rate-limit 거절이 실제로 왔을 때인 것이
  // 핵심이다. 정상 전송도 0.8초 cooldown 을 걸기 때문에, 시각만 보고 띄우면 말 한 줄 보낼
  // 때마다 "조금 빠릅니다" 가 뜬다.
  //
  // 렌더 중에 붙잡는 이유: 효과로 미루면 안내가 뜬 첫 프레임이 초읽기 없는 옛 문구로 한 번
  // 그려진다. 같은 입력에 같은 결과가 나오는 단조 증가 latch 라 두 번 그려도 값이 같다.
  const rateLimitUntilRef = useRef(0);
  if (notice === CHAT_ERROR_MESSAGE.CHAT_TOO_FAST && cooldownUntil > rateLimitUntilRef.current) {
    rateLimitUntilRef.current = cooldownUntil;
  }
  const rateLimitUntil = rateLimitUntilRef.current;

  // 벌칙이 걸려 있는 동안에만 도는 시계. **여기서 전송을 다시 시도하지 않는다** — 대기가
  // 끝나도 보내는 것은 사용자가 누른 Enter 다. 대기가 지나면 이 시계의 마지막 한 번이
  // 안내를 걷어 간다.
  //
  // 250ms 로 도는 것은 표시 단위가 아니라 숫자가 넘어가는 **시점** 때문이다. 1초마다 재면
  // 3→2 가 실제보다 최대 1초 늦게 넘어가 남은 시간이 사실과 어긋난다.
  const cooling = rateLimitUntil > Date.now();
  const [, tick] = useState(0);
  useEffect(() => {
    if (!cooling) return;
    const id = setInterval(() => tick((n) => n + 1), 250);
    return () => clearInterval(id);
  }, [cooling, rateLimitUntil]);

  // 남은 시간은 모델이 이미 들고 있던 `cooldownUntil` 을 붙잡아 둔 값이다 — 화면을 그리려고
  // 정책을 새로 만들지 않는다. 서버가 `retryAfterMs` 를 줬으면 그 값이 이미 여기 반영돼 있다.
  const cooldownSec = cooling ? Math.ceil(Math.max(0, rateLimitUntil - Date.now()) / 1000) : 0;
  // 모델이 준 notice 가 rate-limit 이 아니라면 그것이 더 최근의 거절이다 — 그쪽을 먼저 적는다.
  const otherNotice = notice !== null && notice !== CHAT_ERROR_MESSAGE.CHAT_TOO_FAST ? notice : null;
  // rate-limit 안내는 **대기가 남아 있는 동안만** 쓴다. 모델의 notice 가 아직 살아 있어도
  // (TTL 6초 > 5초 벌칙) 대기가 끝났으면 "기다리라" 는 이미 틀린 말이다 — 그 시점의 사용자는
  // 막혀 있지 않다(`validateWorldChat` 이 통과시킨다). 전할 것이 없으므로 자리를 비운다.
  //
  // 초읽기는 문장을 통째로 만들지 않고 숫자만 따로 감싼다. 남은 초가 이 안내에서 제일 중요한
  // 정보인데 문장에 섞어 두면 묻힌다.
  const noticeView: { head: string; tail: ReactNode } | null = otherNotice !== null
    ? splitNotice(otherNotice)
    : cooldownSec > 0
      ? {
        head: '조금 빠릅니다',
        tail: <><span className="world-chat-notice-count">{cooldownSec}초</span> 후 다시 보낼 수 있어요</>,
      }
      : null;

  // 게스트는 연결 자체를 시도하지 않는다 — 그 상태를 끊김으로 알리면 거짓말이 된다
  const linkText = member && offline
    ? (status === 'reconnecting' ? '채팅 연결 중…' : '채팅 연결이 끊어졌습니다')
    : null;

  // 방금 들어온 사람은 **아직 아무도 말하지 않았을 때만** 알린다. 말 한 줄이 오면 그 자리를
  // 내준다 — 지나간 입장을 계속 띄우면 광장에서 제일 오래된 사실이 제일 위에 남는다.
  const lastEntry = messages[messages.length - 1];
  const joinFlash = lastEntry !== undefined && isJoinNotice(lastEntry) ? lastEntry : undefined;

  /** 거절 사유 — 읽히는 것과 보이는 것을 가른다(아래 주석) */
  const noticeLine = noticeView === null ? null : (
    <p key="notice" className="world-chat-notice">
      {/* 보조기기가 읽는 것은 모델이 준 **원문 하나**다. 눈에 보이는 쪽만 두 덩어리로 갈라
          초읽기를 갈아 끼운다 — 숫자가 바뀔 때마다 alert 가 다시 울리면 3초·2초·1초를 연달아 읽는다. */}
      <span className="world-chat-sr" role="alert">{notice}</span>
      <span className="world-chat-notice-body" aria-hidden="true">
        <span className="world-chat-notice-head">{noticeView.head}</span>
        {noticeView.tail !== null && (
          <span className="world-chat-notice-tail">{noticeView.tail}</span>
        )}
      </span>
    </p>
  );

  /**
   * 시스템 안내 **한 줄**. Passive 와 Active 가 같은 값을 쓰고, 다른 것은 놓이는 자리뿐이다.
   *
   * 적을 것이 없으면 `null` 이고 그때는 **자리 자체를 만들지 않는다.** 한동안은 비어 있어도
   * 높이를 지키는 행으로 뒀는데, 문구가 생겨도 입력칸이 안 움직인다는 점은 맞았지만 정상 상태 —
   * 즉 거의 모든 시간 — 에 입력칸 위로 빈 띠가 남았다.
   *
   * 여러 줄로 쌓지 않는다. 셋 중 하나만 나온다.
   */
  const systemNotice = noticeLine !== null
    ? noticeLine
    : linkText !== null
      ? (
        // 월드에 막 들어온 순간의 한 프레임짜리 'disconnected' 를 보여 주지 않으려고 CSS 로
        // 늦게 띄운다 — 그 사이에 연결이 서면 아무것도 뜨지 않는다.
        <p key="link" className="world-chat-status world-chat-status-late" role="status">
          {linkText}
        </p>
      )
      : joinFlash !== undefined
        ? (
          <p key={'join-' + String(joinFlash.seq)} className="world-chat-flash" role="status">
            {joinFlash.nickname}님이 입장하셨습니다.
          </p>
        )
        : null;

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

      {/* Passive 일시 안내 — 대화 위에 잠깐 떴다 지는 한 줄. 채팅 4줄을 밀어내지 않도록 자리를
          따로 두고, 가운데로 모아 대화 column 과 눈으로 갈라 둔다. Active 는 같은 값을 패널 안
          상단 중앙에 띄운다(아래) — 내용은 하나이고 놓이는 자리만 다르다. */}
      {!open && systemNotice !== null && (
        <div className="world-chat-systemline">{systemNotice}</div>
      )}

      {/* Message Viewport — 로그와 **떠 있는 것들**(새 메시지 배지 · 상태 안내)이 한 상자에 산다.
          떠 있는 쪽은 flow 를 차지하지 않으므로 떴다 사라져도 Composer 와 패널 높이가 그대로다.
          형제로 두면 그때마다 아래가 밀려 입력칸이 위아래로 움직인다. */}
      {/* 받은 말이 없으면 Passive 에서는 상자를 그리지 않는다. 게스트에게 빈 상자만 남던 자리이고,
          회원도 첫 말이 오기 전까지는 월드를 가릴 이유가 없다. */}
      {(open || shown.length > 0) && (
        <div className="world-chat-messages">
          {shown.length > 0 ? (
            <ul
              ref={logRef}
              className="world-chat-log"
              aria-label="월드 채팅"
              onScroll={(event) => {
                atBottomRef.current = isAtBottom(event.currentTarget);
                if (atBottomRef.current) setUnread(0);
              }}
            >
              {shown.map((message) => (
                <li key={message.seq}>
                  <b>{message.nickname}</b> {message.content}
                </li>
              ))}
            </ul>
          ) : (
            /* 저장이 없다는 사실은 한 번만, 그것도 열었을 때만 말한다 (S15P21A604-791) */
            <p className="world-chat-hint">월드 채팅은 접속 중인 동안만 표시됩니다</p>
          )}

          {/* 시스템 안내 — 패널 **상단 중앙**에 떠 있는 작은 알약이다. 한동안 Composer 바로 위에
              뒀는데 거기는 방금 온 말이 지나가는 자리라 읽는 줄을 정확히 가렸다. 위쪽은 이미 읽은
              줄이 밀려 올라가는 쪽이라 가려도 잃는 것이 적다. */}
          {open && systemNotice !== null && (
            <div className="world-chat-notice-float">{systemNotice}</div>
          )}

          {/* 새 메시지 배지는 아래쪽에 그대로 둔다 — 눌러서 내려가는 버튼이라 내려갈 방향에 있어야
              하고, 안내가 위로 옮겨 가 서로 닿을 일이 없다. 둘 다 flow 밖이라 자리를 뺏지 않는다. */}
          {open && unread > 0 && (
            <button type="button" className="world-chat-unread" onClick={jumpToLatest}>
              새 메시지 {unread}개 ↓
            </button>
          )}
        </div>
      )}

      {/* Composer — **Active 에서만 보인다**. 한동안 Passive 에도 띄워 뒀는데(2026-09-16), 월드 위에
          입력칸이 늘 떠 있으니 게임 화면이 아니라 웹 채팅으로 읽혔다. 지금은 Active 에서만 드러난다.

          **DOM 에서 빼지는 않는다.** Enter 로 여는 순간 곧바로 focus 가 가야 하고(위 effect),
          게스트가 focus 했을 때 이유를 말하는 경로(noticeMemberOnly)도 살아 있어야 한다 — 감추는 일은
          CSS 가 하고 구조는 그대로 둔다. 열고 닫는 키는 여전히 Enter 하나다. */}
      <div className={'world-chat-composer' + (offline ? ' world-chat-composer-offline' : '')}>
        <input
          id={WORLD_CHAT_INPUT_ID}
          ref={inputRef}
          className={over ? 'world-chat-input world-chat-input-over' : 'world-chat-input'}
          value={draft}
          // 게스트는 칸에 들어오되 쓰지는 못한다. disabled 로 두면 focus 자체가 오지 않아 왜 못
          // 쓰는지 알려 줄 자리가 없다 — readOnly 로 두고 focus 가 왔을 때 이유를 말한다.
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
