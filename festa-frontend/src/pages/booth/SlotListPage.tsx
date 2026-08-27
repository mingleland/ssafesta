// 부스 슬롯 목록·임대 화면(spec 004 US1·US2 + 003 잔액 소비) — /app/booths.
// 슬롯 조회는 공개(guest-allowed), 임대는 member만(FR-016 — 게스트에겐 목록을 보여준 뒤 안내).
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../../features/auth/model/session';
import { isApiError } from '../../shared/api/client';
import { leaseApi } from '../../entities/booth/leaseApi.select';
import { formatRemaining, remainingMs } from '../../entities/booth/remaining';
import { useLeaseSlot } from '../../features/booth/model/useLeaseSlot';
import { LeaseConfirmDialog } from '../../features/booth/ui/LeaseConfirmDialog';
import { WalletBadge } from '../../features/wallet/ui/WalletBadge';
import { TransactionsSection } from '../../features/wallet/ui/TransactionsSection';
import { LEASE_COIN_COST } from '../../entities/booth/types';
import type { SlotView } from '../../entities/booth/types';

// 임대 실패를 사용자 언어로 — code로만 분기한다(INSUFFICIENT_COIN의 부족액 숫자는 message에만
// 있고 구조화 필드가 없어 파싱 금지, 잔액은 WalletBadge가 invalidate로 최신화된다)
function leaseErrorText(error: unknown): string {
  if (!isApiError(error)) return '임대 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.';
  switch (error.code) {
    case 'INSUFFICIENT_COIN':
      return '코인이 부족하여 임대할 수 없습니다.';
    case 'ACTIVE_LEASE_LIMIT':
      return '부스는 1개만 임대할 수 있습니다.';
    case 'BOOTH_SLOT_ALREADY_LEASED':
      return '방금 다른 회원이 이 슬롯을 임대했습니다. 목록을 새로고침했습니다.';
    case 'BOOTH_SLOT_NOT_RENTABLE':
      return '임대할 수 없는 슬롯입니다.';
    default:
      return error.message;
  }
}

// endsAt 절대시각 기준 1초 카운트다운(FR-007). 0 도달은 "표기"만 만료로 바꾸고 onExpire 1회 —
// 상태 전환(AVAILABLE 복귀·버튼 활성)은 refetch된 서버 status가 권위다(C-02, 시계 skew 안전).
function RemainingTime({ endsAt, onExpire }: { endsAt: string; onExpire?: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  const expiredNotified = useRef(false);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const ms = remainingMs(endsAt, now);

  useEffect(() => {
    if (ms === 0 && !expiredNotified.current) {
      expiredNotified.current = true;
      onExpire?.();
    }
  }, [ms, onExpire]);

  if (ms === 0) return <span>만료</span>;
  return <span>남은 시간 {formatRemaining(ms)}</span>;
}

function slotStatusLabel(slot: SlotView): string {
  if (slot.status === 'AVAILABLE') return slot.type === 'USER_RENTAL' ? '임대 가능' : '운영 부스';
  return slot.mine ? '내 부스' : `사용 중 — ${slot.boothName ?? ''}`;
}

export function SlotListPage() {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const queryClient = useQueryClient();

  const slotsQuery = useQuery({ queryKey: ['booth-slots'], queryFn: leaseApi.getSlots });
  const myBoothQuery = useQuery({
    queryKey: ['my-booth'],
    queryFn: leaseApi.getMyBooth,
    enabled: isMember, // 게스트는 403 — 요청 자체를 만들지 않는다
  });
  const leaseMutation = useLeaseSlot();
  // 확인 중인 슬롯 — 100코인 차감은 환불이 없으므로(FR-013) 버튼이 곧바로 요청하지 않는다
  const [confirming, setConfirming] = useState<SlotView | null>(null);

  const invalidateSlots = () => {
    queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
    queryClient.invalidateQueries({ queryKey: ['my-booth'] });
  };

  if (slotsQuery.isLoading) return <div>불러오는 중...</div>;
  if (slotsQuery.isError) return <p>슬롯 목록을 불러오지 못했습니다.</p>;

  const slots = slotsQuery.data ?? [];
  const myBooth = myBoothQuery.data ?? null;

  return (
    <div>
      <h1>부스 슬롯</h1>
      <WalletBadge />

      {myBooth && myBooth.lease && (
        <section>
          <h2>내 부스</h2>
          <p>
            {myBooth.name} · {myBooth.lease.slotCode ?? '슬롯 미연결'} ·{' '}
            <RemainingTime endsAt={myBooth.lease.endsAt} onExpire={invalidateSlots} />
          </p>
          <Link to={`/app/studio/${myBooth.boothId}`}>스튜디오에서 편집</Link>
        </section>
      )}

      <ul>
        {slots.map((slot) => (
          <li key={slot.slotId}>
            <strong>{slot.slotCode}</strong> — {slotStatusLabel(slot)}
            {slot.status === 'OCCUPIED' && slot.leaseEndsAt && (
              <>
                {' · '}
                <RemainingTime endsAt={slot.leaseEndsAt} onExpire={invalidateSlots} />
              </>
            )}
            {slot.status === 'AVAILABLE' && slot.type === 'USER_RENTAL' && isMember && (
              <>
                {' '}
                <button
                  type="button"
                  onClick={() => setConfirming(slot)}
                  disabled={leaseMutation.isPending}
                >
                  1일 임대 ({LEASE_COIN_COST}코인)
                </button>
              </>
            )}
            {slot.status === 'AVAILABLE' && slot.type === 'USER_RENTAL' && !isMember && (
              <>
                {' · '}
                <span>
                  임대는 소셜 로그인 회원만 가능합니다. <Link to="/login">로그인</Link>
                </span>
              </>
            )}
          </li>
        ))}
      </ul>

      {confirming && (
        <LeaseConfirmDialog
          slot={confirming}
          pending={leaseMutation.isPending}
          onConfirm={() =>
            // 성공이든 실패든 닫는다 — 실패 사유는 아래 alert가 목록 맥락에서 보여준다
            leaseMutation.mutate(confirming.slotId, { onSettled: () => setConfirming(null) })
          }
          onCancel={() => setConfirming(null)}
        />
      )}

      {leaseMutation.isError && <p role="alert">{leaseErrorText(leaseMutation.error)}</p>}

      {isMember && (
        <details>
          <summary>코인 사용 내역</summary>
          <TransactionsSection />
        </details>
      )}
    </div>
  );
}
