// Lease-expiry WebSocket consumer — turns the owner's private D07 reminder into a React toast.
import { BOOTH_LEASE_EXPIRY_QUEUE } from '../../../shared/realtime/destinations';
import { connectRealtime, subscribeRealtime } from '../../../shared/realtime/realtimeClient';
import { showToast } from '../../../shared/ui/toast/toastStore';

interface LeaseExpiryWarning {
  type: 'lease-expiring';
  leaseId: string;
  boothId: string;
  endsAt: string;
  remainingSeconds: number;
}

function isLeaseExpiryWarning(value: unknown): value is LeaseExpiryWarning {
  if (value === null || typeof value !== 'object') return false;
  const event = value as Partial<LeaseExpiryWarning>;
  return event.type === 'lease-expiring'
    && typeof event.leaseId === 'string'
    && typeof event.boothId === 'string'
    && typeof event.endsAt === 'string'
    && typeof event.remainingSeconds === 'number';
}

function receiveLeaseExpiryWarning(raw: string): void {
  try {
    const event: unknown = JSON.parse(raw);
    if (!isLeaseExpiryWarning(event)) throw new Error('unknown lease-expiry event');
    showToast('내 부스 임대가 약 1시간 뒤 만료됩니다.', 'info');
  } catch (error) {
    // The event is an alert, not data the screen can safely infer. Leave diagnostic evidence rather
    // than presenting a potentially false expiry notice (T-24: do not swallow malformed input).
    console.warn('[leaseExpiryNotification] 읽을 수 없는 만료 임박 알림을 버렸습니다.', error);
  }
}

/** Starts the shared real-time transport and returns the cleanup for this consumer only. */
export function startLeaseExpiryNotifications(): () => void {
  const stop = subscribeRealtime(BOOTH_LEASE_EXPIRY_QUEUE, receiveLeaseExpiryWarning);
  void connectRealtime().catch((error: unknown) => {
    console.error('[leaseExpiryNotification] 실시간 연결 실패 —', error);
  });
  return stop;
}
