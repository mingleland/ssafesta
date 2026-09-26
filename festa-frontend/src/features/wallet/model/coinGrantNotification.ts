import { queryClient } from '../../../app/providers/queryClient';
import { walletApi } from '../../../entities/wallet/api.select';
import { labelForReason } from '../../../entities/wallet/types';
import { COIN_QUEUE } from '../../../shared/realtime/destinations';
import { connectRealtime, subscribeRealtime } from '../../../shared/realtime/realtimeClient';
import { showToast } from '../../../shared/ui/toast/toastStore';

export interface CoinGrantNotification {
  type: 'granted';
  entryId: string;
  amount: number;
  balanceAfter: number;
  reasonType: string;
  referenceType: string | null;
  referenceId: string | null;
  occurredAt: string;
}

export function isCoinGrantNotification(value: unknown): value is CoinGrantNotification {
  if (value === null || typeof value !== 'object') return false;
  const event = value as Partial<CoinGrantNotification>;
  return (
    event.type === 'granted' &&
    typeof event.entryId === 'string' &&
    typeof event.amount === 'number' &&
    typeof event.balanceAfter === 'number' &&
    typeof event.reasonType === 'string' &&
    typeof event.occurredAt === 'string'
  );
}

/**
 * 토스트를 띄우지 않는 지급 사유 (S15P21A604-949).
 *
 * 슬롯은 한 번 앉으면 수십 번 돌리는 기계라 당첨마다 알림이 뜨면 그 알림이 월드를 덮는다. 결과는
 * 슬롯 화면이 그 자리에서 이미 보여 주므로 여기서 한 번 더 말할 것이 없다.
 *
 * 일일 미션 보상도 같다. 지급 경로가 미션 창의 "받기" 하나뿐이고, 그 창이 응답을 받아 "일일 미션
 * 보상으로 N 코인을 받았습니다" 를 이미 띄운다. 여기서도 알리면 같은 지급이 토스트 두 줄로 겹친다.
 *
 * **잔액·내역 갱신은 건너뛰지 않는다.** 감추는 것은 알림 한 줄뿐이고, HUD 잔액과 코인 사용 내역은
 * 종전대로 따라간다 — 원장에서 사라지면 그건 감사 기록을 지우는 것이다.
 *
 * 실시간 경로·catch-up 경로 둘 다 이 함수 하나를 거치게 해서(리뷰 지적, S15P21A604-953) 두 경로가
 * 각자 규칙을 들고 있다가 한쪽만 고치는 일을 막는다.
 */
const SILENT_REASONS: ReadonlySet<string> = new Set(['SLOT_PAYOUT', 'DAILY_MISSION']);

/**
 * 월드에 들어서기 전까지 코인 토스트를 붙들어 둔다.
 *
 * 가입·일일 지급은 로그인 직후 한꺼번에 오는데 그때 화면은 아직 "게임을 준비하고 있어요" 로딩
 * 판이다 — 토스트가 그 위에 떴다 혼자 사라져 사용자는 받은 줄을 모른다.
 *
 * **붙드는 것은 알림 한 줄뿐이다.** 잔액·내역 invalidate 와 워터마크는 종전대로 그 자리에서
 * 간다 — SILENT_REASONS 와 같은 규칙이다. 미루는 것이지 버리는 것이 아니다.
 *
 * 붙드는 쪽은 `WorldPage` 다. "들어왔다" 를 아는 곳이 거기 하나이기 때문이고, 월드를 거치지
 * 않는 화면(게임 목록·관리자 콘솔 등)에서는 아무도 붙들지 않으므로 종전대로 즉시 뜬다.
 */
let holdingToasts = false;
const pendingToasts: string[] = [];

export function holdCoinGrantToasts(): void {
  holdingToasts = true;
}

/** 붙들기를 풀고 그동안 쌓인 것을 낸다. 입장 순간과 월드를 떠나는 순간 둘 다 여기로 온다. */
export function releaseCoinGrantToasts(): void {
  holdingToasts = false;
  for (const message of pendingToasts) showToast(message, 'success');
  pendingToasts.length = 0;
}

function announceGrant(amount: number, reasonType: string): void {
  if (SILENT_REASONS.has(reasonType)) return;
  const reasonText = labelForReason(reasonType);
  const message = `${reasonText} +${amount} 코인이 지급되었습니다.`;
  if (holdingToasts) {
    pendingToasts.push(message);
    return;
  }
  showToast(message, 'success');
}

/**
 * 구독 전에 이미 지급됐을 수 있는 사유들 (S15P21A604-953) — 로그인·가입 처리 중 서버가
 * 그 자리에서 지급해, FE가 WS를 구독하기 전에 이벤트가 발행되고 사라진다.
 * DAILY_MISSION 등 사용자 행동으로 발생하는 지급은 이미 WS가 연결된 뒤라 이 경로가 필요 없다.
 */
const CATCH_UP_REASONS = new Set(['INITIAL_GRANT', 'DAILY_GRANT']);

/**
 * catch-up이 "방금 놓친 지급"으로 인정하는 나이. 이보다 오래된 건은 알리지 않고 워터마크만
 * 올려 조용히 넘긴다 (리뷰 지적, S15P21A604-953) — 워터마크가 없는 첫 방문(새 브라우저·시크릿창)
 * 이 최근 20건 안의 지난 DAILY_GRANT 를 전부 토스트로 쏟아내는 것을 막는다.
 */
const RECENT_GRANT_WINDOW_MS = 5 * 60 * 1000;

// catch-up 토스트 중복 방지 워터마크 — 이미 알려준 거래 id 이하는 다시 띄우지 않는다.
// localStorage는 읽기·쓰기 모두 던질 수 있어(사생활 보호 모드) worldGuide.ts와 같은 guard를 쓴다:
// 실패하면 "본 적 없다"로 취급한다 — 토스트가 한 번 더 뜨는 것은 실패가 아니다.
const LAST_ANNOUNCED_KEY = 'festa.wallet.lastAnnouncedGrantId';

function readLastAnnouncedId(): number {
  try {
    return Number(window.localStorage.getItem(LAST_ANNOUNCED_KEY)) || 0;
  } catch {
    return 0;
  }
}

function writeLastAnnouncedId(id: number): void {
  try {
    window.localStorage.setItem(LAST_ANNOUNCED_KEY, String(id));
  } catch {
    // 저장 못 하면 다음에 한 번 더 뜬다 — 그 이상 할 것이 없다.
  }
}

export function receiveCoinGrantNotification(raw: string): void {
  try {
    const event: unknown = JSON.parse(raw);
    if (!isCoinGrantNotification(event)) throw new Error('unknown coin grant event');

    announceGrant(event.amount, event.reasonType);

    if (CATCH_UP_REASONS.has(event.reasonType)) {
      const id = Number(event.entryId);
      if (Number.isFinite(id) && id > readLastAnnouncedId()) writeLastAnnouncedId(id);
    }

    // 화면 잔액 계산 원칙: 이벤트의 amount로 클라이언트 자체 누적 연산을 하지 않고,
    // refetch된 REST 응답(balance)을 최종 화면 상태 정본으로 사용한다 (S15P21A604-920).
    void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    void queryClient.invalidateQueries({ queryKey: ['wallet-transactions'] });
  } catch (error) {
    console.warn('[coinGrantNotification] 읽을 수 없는 코인 지급 알림을 버렸습니다.', error);
  }
}

/**
 * 실시간 코인 알림을 시작한다 (S15P21A604-920, S15P21A604-923, S15P21A604-953).
 * 구독 후 초기 transactions 1회 조회로 구독 전 발생한 미수신 지급분을 동기화하고,
 * 그중 최근에 놓친 가입·일일 지급은 토스트를 대신 띄운다.
 */
export function startCoinGrantNotifications(): () => void {
  // **여기서 바로 붙든다.** WorldPage 가 붙들기를 설치하기 전에 이 catch-up 이 먼저 끝나 버린다 —
  // 로그인 처리(세션이 member 가 되는 순간)가 월드 라우트 진입보다 앞서기 때문이다. 그 경합에서
  // 가입·일일 지급 토스트가 로딩 판 위로 새어 나갔다. 구독을 시작하는 이 자리가 catch-up 을
  // 띄우는 자리와 같은 동기 호출 안이라, 여기서 켜면 새어 나갈 틈이 없다.
  holdCoinGrantToasts();

  const stop = subscribeRealtime(COIN_QUEUE, receiveCoinGrantNotification);

  // S15P21A604-923/953: 구독 전에 발행된 지급(가입 INITIAL_GRANT, 첫 접속 DAILY_GRANT)은
  // STOMP에 재전송이 없어 유실된다 — 잔액뿐 아니라 토스트도 놓친다. 구독 직후 REST 트랜잭션
  // 1회 조회로 잔액을 메우고, 그중 방금 놓친 지급 건은 같은 토스트를 대신 띄운다.
  void walletApi.getTransactions(0).then((page) => {
    const lastAnnounced = readLastAnnouncedId();
    const now = Date.now();
    let maxId = lastAnnounced;
    for (const tx of page.content) {
      if (tx.id > maxId) maxId = tx.id;
      if (!CATCH_UP_REASONS.has(tx.reasonType) || tx.id <= lastAnnounced) continue;
      // 방금 놓친 것만 알린다 — 오래된 지급까지 알리면 워터마크 없는 첫 방문(새 브라우저·
      // 시크릿창)에서 최근 20건 안의 지난 DAILY_GRANT 가 한꺼번에 쏟아진다.
      if (now - new Date(tx.createdAt).getTime() <= RECENT_GRANT_WINDOW_MS) {
        announceGrant(tx.amount, tx.reasonType);
      }
    }
    if (maxId > lastAnnounced) writeLastAnnouncedId(maxId);
    void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
  }).catch((error: unknown) => {
    console.warn('[coinGrantNotification] 초기 트랜잭션 동기화 실패 —', error);
  });

  void connectRealtime().catch((error: unknown) => {
    console.error('[coinGrantNotification] 실시간 연결 실패 —', error);
  });

  // 세션이 끝나면 붙들기와 대기분을 버린다. 남겨 두면 다음 로그인의 입장 순간에 **남의 계정**
  // 토스트가 튀어나온다. 버려도 원장·잔액에는 그대로 남아 있다.
  return () => {
    stop();
    pendingToasts.length = 0;
    holdingToasts = false;
  };
}
