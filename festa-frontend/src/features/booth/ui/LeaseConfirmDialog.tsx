// 임대 확인 모달(S15P21A604-171) — 100코인 차감은 변심 환불이 없으므로(FR-013) 요청 전에 한 번 세운다.
// 출처: specs/004-booth-slot-lease/spec.md FR-013·FR-006, contracts/lease-api.md
import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { walletApi } from '../../../entities/wallet/api.select';
import { LEASE_COIN_COST } from '../../../entities/booth/types';
import type { SlotView } from '../../../entities/booth/types';
import { judgeAffordability } from '../model/leaseConfirm';

interface Props {
  slot: SlotView;
  pending: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function LeaseConfirmDialog({ slot, pending, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  // <dialog>를 쓰는 이유는 Esc 닫기·포커스 가둠·backdrop을 직접 구현하지 않기 위해서다.
  // 열림은 showModal()로만 일어난다 — open 속성으로 열면 그 셋이 전부 빠진다.
  useEffect(() => {
    ref.current?.showModal();
  }, []);

  // WalletBadge와 같은 queryKey — 캐시를 공유하므로 모달을 연다고 요청이 늘지 않는다.
  const walletQuery = useQuery({ queryKey: ['wallet-balance'], queryFn: walletApi.getWallet });
  const balance = walletQuery.data?.balance;
  const affordability = judgeAffordability(balance, LEASE_COIN_COST);

  return (
    <dialog ref={ref} onCancel={onCancel} aria-labelledby="lease-confirm-title">
      <h2 id="lease-confirm-title">부스 슬롯 임대</h2>
      <p>
        <strong>{slot.slotCode}</strong> 슬롯을 24시간 임대합니다.
      </p>
      <dl>
        <dt>차감 코인</dt>
        <dd>{LEASE_COIN_COST}코인</dd>
        <dt>보유 코인</dt>
        <dd>{balance === undefined ? '확인 중' : `${balance}코인`}</dd>
      </dl>
      {/* 부족해 보여도 요청은 막지 않는다 — 서버가 응답 전에 일일 지급을 반영하므로 이 값이 낮을 수 있다 */}
      {affordability === 'insufficient' && (
        <p role="alert">보유 코인이 부족해 보입니다. 요청은 서버 잔액으로 다시 판정됩니다.</p>
      )}
      {walletQuery.isError && <p role="alert">잔액을 불러오지 못했습니다. 차감 후 내역에서 확인해 주세요.</p>}
      <p>임대 후에는 변심에 의한 환불이 되지 않습니다.</p>
      <button type="button" onClick={onConfirm} disabled={pending}>
        {pending ? '요청 중...' : `${LEASE_COIN_COST}코인 차감하고 임대`}
      </button>
      <button type="button" onClick={onCancel} disabled={pending}>
        취소
      </button>
    </dialog>
  );
}
