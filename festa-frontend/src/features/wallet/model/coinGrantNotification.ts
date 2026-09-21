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

function announceGrant(amount: number, reasonType: string): void {
  const reasonText = labelForReason(reasonType);
  showToast(`${reasonText} +${amount} 코인이 지급되었습니다.`, 'success');
}

/**
 * 구독 전에 이미 지급됐을 수 있는 사유들 (S15P21A604-953) — 로그인·가입 처리 중 서버가
 * 그 자리에서 지급해, FE가 WS를 구독하기 전에 이벤트가 발행되고 사라진다.
 * DAILY_MISSION 등 사용자 행동으로 발생하는 지급은 이미 WS가 연결된 뒤라 이 경로가 필요 없다.
 */
const CATCH_UP_REASONS = new Set(['INITIAL_GRANT', 'DAILY_GRANT']);

// 캐치업 토스트 중복 방지 워터마크 — 이미 알려준 거래 id 이하는 다시 띄우지 않는다.
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
 * 그중 가입·일일 지급은 놓친 토스트를 대신 띄운다.
 */
export function startCoinGrantNotifications(): () => void {
  const stop = subscribeRealtime(COIN_QUEUE, receiveCoinGrantNotification);

  // S15P21A604-923/953: 구독 전에 발행된 지급(가입 INITIAL_GRANT, 첫 접속 DAILY_GRANT)은
  // STOMP에 재전송이 없어 유실된다 — 잔액뿐 아니라 토스트도 놓친다. 구독 직후 REST 트랜잭션
  // 1회 조회로 잔액을 메우고, 아직 알리지 않은 지급 건은 같은 토스트를 대신 띄운다.
  void walletApi.getTransactions(0).then((page) => {
    const lastAnnounced = readLastAnnouncedId();
    let maxId = lastAnnounced;
    for (const tx of page.content) {
      if (tx.id > maxId) maxId = tx.id;
      if (CATCH_UP_REASONS.has(tx.reasonType) && tx.id > lastAnnounced) {
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

  return stop;
}
