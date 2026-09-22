// 부스 임대 — 월드 위 오버레이 (2026-09-17).
//
// 옛 `/app/booths` 전체 페이지를 대체한다. 그쪽은 route 를 갈아타 월드를 떠났고(그때마다 Unity 가
// 언마운트됐다), 평면도 아래에 같은 슬롯을 카드로 한 번 더 그렸다. **맵이 곧 선택 UI**이므로 목록을
// 두지 않고, 고른 자리의 정보만 오른쪽에 둔다.
//
// 기능층은 그대로다 — 슬롯 조회·`useLeaseSlot`·확인 모달·오류 문구 매핑은 옛 화면에서 옮겨 왔다.
import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../../auth/model/session';
import { isApiError } from '../../../shared/api/client';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { LEASE_COIN_COST } from '../../../entities/booth/types';
import type { SlotView } from '../../../entities/booth/types';
import { remainingMs } from '../../../entities/booth/remaining';
import { openManagement } from '../../world/model/worldScreen';
import { useLeaseSlot } from '../model/useLeaseSlot';
import { useBoothPublish } from '../model/boothPublication';
import { compose } from '../model/presentationComposer';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { LeaseConfirmDialog } from './LeaseConfirmDialog';
import { SlotMap } from './SlotMap';
import { WalletBadge } from '../../wallet/ui/WalletBadge';
import { OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import './boothRental.css';

const IcSlots = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="3" width="7" height="8" rx="1.6" />
    <rect x="14" y="3" width="7" height="8" rx="1.6" />
    <rect x="3" y="13" width="7" height="8" rx="1.6" />
    <rect x="14" y="13" width="7" height="8" rx="1.6" />
  </svg>
);

/** 임대 실패를 사용자 언어로. code 로만 분기한다 — 부족액 숫자는 message 문자열에만 있고 파싱은 금지다 */
function leaseErrorText(error: unknown): string {
  if (!isApiError(error)) return '임대 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.';
  switch (error.code) {
    case 'INSUFFICIENT_COIN':
      return '코인이 부족하여 임대할 수 없습니다.';
    case 'ACTIVE_LEASE_LIMIT':
      return '부스는 1개만 임대할 수 있습니다.';
    case 'BOOTH_SLOT_ALREADY_LEASED':
      return '방금 다른 회원이 이 자리를 임대했습니다. 자리 상태를 다시 읽었습니다.';
    case 'BOOTH_SLOT_NOT_RENTABLE':
      return '임대할 수 없는 자리입니다.';
    default:
      return error.message;
  }
}

export function BoothRentalOverlay({ onClose }: { onClose: () => void }) {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const queryClient = useQueryClient();

  const slotsQuery = useQuery({ queryKey: ['booth-slots'], queryFn: leaseApi.getSlots });
  // 임대 중이면 이 화면은 볼 일이 없다 — 어느 길(?panel=rental·"부스 임대하기" 버튼)로 왔든 관리 화면으로
  // 돌린다 (S15P21A604-855). 만료된 임대는 막지 않는다: 그건 관리 화면이 "임대 만료" 로 다루는 상태다.
  const myBoothQuery = useQuery({ queryKey: ['my-booth'], queryFn: leaseApi.getMyBooth, enabled: isMember });
  const activeLease = myBoothQuery.data?.lease ?? null;
  const alreadyLeasing = activeLease !== null && remainingMs(activeLease.endsAt, Date.now()) > 0;
  useEffect(() => {
    if (alreadyLeasing) openManagement();
  }, [alreadyLeasing]);

  const lease = useLeaseSlot();
  // 임대와 게시를 한 동작으로 묶는다 (S15P21A604-959). 전에는 임대만 하면 자리는 잡히는데 월드에는
  // 아무것도 보이지 않았고, 사용자는 관리창에서 "게시" 를 한 번 더 눌러야 그제서야 부스가 섰다.
  //
  // 게시 내용은 빈 전시다(`aiAgentId: null`) — 임대 시점에는 프로젝트도 AI 직원도 없다. 관리창에서
  // 내용을 채운 뒤 "변경사항 적용" 이 같은 경로로 덮어쓴다.
  const publish = useBoothPublish();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  // 100코인은 환불이 없다(FR-013) — 버튼이 곧바로 요청하지 않는다
  const [confirming, setConfirming] = useState<SlotView | null>(null);

  const slots = slotsQuery.data ?? [];
  const selected = slots.find((slot) => slot.slotId === selectedId) ?? null;
  const rentable = selected !== null && selected.type === 'USER_RENTAL' && selected.status === 'AVAILABLE' && !selected.mine;

  return (
    <OverlayFrame
      title="부스 선택"
      subtitle="원하는 자리를 선택하세요"
      size="xl"
      icon={IcSlots}
      onClose={onClose}
      status={<WalletBadge />}
    >
      {(slotsQuery.isLoading || myBoothQuery.isLoading || alreadyLeasing) && <OverlayLoading label="자리를 불러오는 중..." />}
      {slotsQuery.isError && (
        <OverlayError title="자리를 불러오지 못했습니다" onRetry={() => void slotsQuery.refetch()} />
      )}

      {slotsQuery.isSuccess && !myBoothQuery.isLoading && !alreadyLeasing && (
        <div className="br-body br-width">
          <div className="br-map">
            <SlotMap slots={slots} selectedId={selectedId} onSelect={(slot) => setSelectedId(slot.slotId)} />
          </div>

          {/* 오른쪽은 고른 자리 하나만 말한다 — 번호·상태·가격·행동 */}
          <aside className="br-panel" aria-label="선택한 자리">
            {selected === null ? (
              <p className="br-empty">맵에서 자리를 고르세요</p>
            ) : (
              <>
                <strong className="br-code">{selected.slotCode}</strong>
                <span className={'br-state br-state-' + (rentable ? 'ok' : selected.mine ? 'mine' : 'off')}>
                  {selected.mine ? '내 부스' : rentable ? '임대 가능' : '선택 불가'}
                </span>
                {rentable && <span className="br-price">{LEASE_COIN_COST}코인 · 1일</span>}

                {rentable && isMember && (
                  <button
                    type="button"
                    className="sc-btn sc-btn-primary br-cta"
                    disabled={lease.isPending}
                    onClick={() => setConfirming(selected)}
                  >
                    임대
                  </button>
                )}
                {rentable && !isMember && <p className="br-note">임대는 소셜 로그인 회원만 가능합니다.</p>}

                {lease.isError && (
                  <p className="br-error" role="alert">{leaseErrorText(lease.error)}</p>
                )}
              </>
            )}
          </aside>
        </div>
      )}

      {confirming && (
        <LeaseConfirmDialog
          slot={confirming}
          pending={lease.isPending}
          onConfirm={() =>
            // 성공이든 실패든 모달은 닫는다 — 실패 사유는 오른쪽 패널이 그 자리에서 말한다.
            // 성공이면 이 화면에 남을 이유가 없다: 바로 관리 화면으로 넘어간다 (S15P21A604-855).
            lease.mutate(confirming.slotId, {
              // 임대가 되면 그 자리에서 게시까지 한다. 게시가 실패해도 관리 화면으로 보낸다 —
              // 자리는 이미 내 것이고, 그 화면이 "게시" 버튼으로 남은 일을 정확히 말해 준다.
              onSuccess: ({ boothId }, slotId) => {
                publish.mutate(
                  { boothId, slotId, presentation: compose({ aiAgent: null, published: null }) },
                  {
                    onError: () => showToast('자리는 잡았지만 공개하지 못했습니다. 관리 화면에서 게시해 주세요.', 'error'),
                    onSettled: () => openManagement(),
                  },
                );
              },
              onSettled: () => {
                setConfirming(null);
                queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
              },
            })
          }
          onCancel={() => setConfirming(null)}
        />
      )}
    </OverlayFrame>
  );
}
