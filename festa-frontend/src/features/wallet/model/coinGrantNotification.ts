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
 * **잔액·내역 갱신은 건너뛰지 않는다.** 감추는 것은 알림 한 줄뿐이고, HUD 잔액과 코인 사용 내역은
 * 종전대로 따라간다 — 원장에서 사라지면 그건 감사 기록을 지우는 것이다.
 */
const SILENT_REASONS: ReadonlySet<string> = new Set(['SLOT_PAYOUT']);

export function receiveCoinGrantNotification(raw: string): void {
  try {
    const event: unknown = JSON.parse(raw);
    if (!isCoinGrantNotification(event)) throw new Error('unknown coin grant event');

    if (!SILENT_REASONS.has(event.reasonType)) {
      const reasonText = labelForReason(event.reasonType);
      showToast(`${reasonText} +${event.amount} 코인이 지급되었습니다.`, 'success');
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
 * 실시간 코인 알림을 시작한다 (S15P21A604-920, S15P21A604-923).
 * 구독 후 초기 transactions 1회 조회로 구독 전 발생한 미수신 지급분을 동기화한다.
 */
export function startCoinGrantNotifications(): () => void {
  const stop = subscribeRealtime(COIN_QUEUE, receiveCoinGrantNotification);

  // S15P21A604-923: 구독 전에 발행된 지급(가입 INITIAL_GRANT, 첫 접속 DAILY_GRANT)은
  // STOMP에 재전송이 없어 유실된다. 구독 직후 REST 트랜잭션 1회 조회로 누락을 메운다.
  void walletApi.getTransactions(0).then(() => {
    void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
  }).catch((error: unknown) => {
    console.warn('[coinGrantNotification] 초기 트랜잭션 동기화 실패 —', error);
  });

  void connectRealtime().catch((error: unknown) => {
    console.error('[coinGrantNotification] 실시간 연결 실패 —', error);
  });

  return stop;
}
