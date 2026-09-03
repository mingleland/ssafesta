// 부스 슬롯 목록·임대 화면(spec 004 US1·US2 + 003 잔액 소비) — /app/booths.
// 슬롯 조회는 공개(guest-allowed), 임대는 member만(FR-016 — 게스트에겐 목록을 보여준 뒤 안내).
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
// 표현은 Screen Local Baseline(PageShell) — 기능·에러 매핑·카운트다운 권위는 그대로다(S15P21A604-406).
// 이 화면은 결제 맥락이라 잔액(WalletBadge)만 보여준다. 전체 거래내역은 My Info 한 곳이 정본이다(D-08).
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
import { PageShell, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import { LEASE_COIN_COST } from '../../entities/booth/types';
import type { SlotView } from '../../entities/booth/types';
import './slotListPage.css';

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

function statusChipClass(slot: SlotView): string {
  if (slot.mine) return 'sc-chip sc-chip-mine';
  if (slot.status === 'AVAILABLE' && slot.type === 'USER_RENTAL') return 'sc-chip sc-chip-ok';
  return 'sc-chip';
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

  if (slotsQuery.isLoading) {
    return (
      <PageShell title="부스 슬롯" backTo="/app/world">
        <ScreenLoading label="슬롯을 불러오는 중..." />
      </PageShell>
    );
  }
  if (slotsQuery.isError) {
    return (
      <PageShell title="부스 슬롯" backTo="/app/world">
        <ScreenError title="슬롯 목록을 불러오지 못했습니다" onRetry={() => void slotsQuery.refetch()} />
      </PageShell>
    );
  }

  const slots = slotsQuery.data ?? [];
  const myBooth = myBoothQuery.data ?? null;
  const availableCount = slots.filter((s) => s.status === 'AVAILABLE' && s.type === 'USER_RENTAL').length;

  return (
    <PageShell
      title="부스 슬롯"
      subtitle={`축제 광장에서 내 부스를 열 자리를 고르세요 · 임대 가능 ${availableCount}곳`}
      backTo="/app/world"
      actions={<WalletBadge />}
    >
      {myBooth && myBooth.lease && (
        <section className="sc-card slot-mybooth">
          <div className="slot-mybooth-info">
            <span className="sc-chip sc-chip-mine">내 부스</span>
            <strong>{myBooth.name}</strong>
            <span className="sc-note">
              {myBooth.lease.slotCode ?? '슬롯 미연결'} · <RemainingTime endsAt={myBooth.lease.endsAt} onExpire={invalidateSlots} />
            </span>
          </div>
          <Link className="sc-btn sc-btn-primary" to={`/app/studio/${myBooth.boothId}`}>
            스튜디오에서 편집
          </Link>
        </section>
      )}

      {leaseMutation.isError && (
        <p className="sc-alert slot-error" role="alert">
          {leaseErrorText(leaseMutation.error)}
        </p>
      )}

      <ul className="slot-grid">
        {slots.map((slot) => {
          const rentable = slot.status === 'AVAILABLE' && slot.type === 'USER_RENTAL';
          return (
            <li key={slot.slotId} className={'slot-card' + (slot.mine ? ' slot-card-mine' : '')}>
              <div className="slot-card-head">
                <strong className="slot-code">{slot.slotCode}</strong>
                <span className={statusChipClass(slot)}>{slotStatusLabel(slot)}</span>
              </div>

              <div className="slot-card-body">
                {slot.status === 'OCCUPIED' && slot.leaseEndsAt && (
                  <span className="sc-note">
                    <RemainingTime endsAt={slot.leaseEndsAt} onExpire={invalidateSlots} />
                  </span>
                )}
                {rentable && <span className="slot-price">{LEASE_COIN_COST} 코인 / 1일</span>}
              </div>

              {rentable && isMember && (
                <button type="button" className="sc-btn sc-btn-primary slot-cta" onClick={() => setConfirming(slot)} disabled={leaseMutation.isPending}>
                  1일 임대 ({LEASE_COIN_COST}코인)
                </button>
              )}
              {rentable && !isMember && (
                <span className="sc-note">
                  임대는 소셜 로그인 회원만 가능합니다. <Link to="/login">로그인</Link>
                </span>
              )}
            </li>
          );
        })}
      </ul>

      {confirming && (
        <LeaseConfirmDialog
          slot={confirming}
          pending={leaseMutation.isPending}
          onConfirm={() =>
            // 성공이든 실패든 닫는다 — 실패 사유는 위 alert가 목록 맥락에서 보여준다
            leaseMutation.mutate(confirming.slotId, { onSettled: () => setConfirming(null) })
          }
          onCancel={() => setConfirming(null)}
        />
      )}

    </PageShell>
  );
}
