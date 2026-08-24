// 부스 슬롯 목록·임대 화면(spec 004 US1·US2 + 003 잔액 소비) — /app/booths.
// 슬롯 조회는 공개(guest-allowed), 임대는 member만(FR-016 — 게스트에겐 목록을 보여준 뒤 안내).
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../../features/auth/model/session';
import { leaseApi } from '../../entities/booth/leaseApi.select';
import { formatRemaining, remainingMs } from '../../entities/booth/remaining';
import type { SlotView } from '../../entities/booth/types';

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

  const invalidateSlots = () => {
    queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
    queryClient.invalidateQueries({ queryKey: ['my-booth'] });
  };

  if (slotsQuery.isLoading) return <div>불러오는 중...</div>;
  if (slotsQuery.isError) return <p>슬롯 목록을 불러오지 못했습니다.</p>;

  const slots = slotsQuery.data ?? [];

  return (
    <div>
      <h1>부스 슬롯</h1>

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
    </div>
  );
}
