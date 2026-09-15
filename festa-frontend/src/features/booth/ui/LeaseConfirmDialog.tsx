// 임대 확인 모달(S15P21A604-171) — 100코인 차감은 변심 환불이 없으므로(FR-013) 요청 전에 한 번 세운다.
// 출처: specs/004-booth-slot-lease/spec.md FR-013·FR-006, contracts/lease-api.md
import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { walletApi } from '../../../entities/wallet/api.select';
import { LEASE_COIN_COST } from '../../../entities/booth/types';
import type { SlotView } from '../../../entities/booth/types';
import { judgeAffordability } from '../model/leaseConfirm';
import './leaseConfirmDialog.css';

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
    <dialog ref={ref} className="lease-confirm" onCancel={onCancel} aria-labelledby="lease-confirm-title">
      <h2 id="lease-confirm-title" className="lease-confirm-title">부스 슬롯 임대</h2>
      <p className="lease-confirm-lead">
        <strong>{slot.slotCode}</strong> 슬롯을 24시간 임대합니다.
      </p>
      <dl className="lease-confirm-figures">
        <dt>차감 코인</dt>
        <dd className="lease-confirm-cost">{LEASE_COIN_COST}코인</dd>
        <dt>보유 코인</dt>
        <dd>{balance === undefined ? '확인 중' : `${balance}코인`}</dd>
      </dl>
      {/* 부족해 보여도 요청은 막지 않는다 — 서버가 응답 전에 일일 지급을 반영하므로 이 값이 낮을 수 있다 */}
      {affordability === 'insufficient' && (
        <p className="lease-confirm-alert" role="alert">보유 코인이 부족해 보입니다. 요청은 서버 잔액으로 다시 판정됩니다.</p>
      )}
      {walletQuery.isError && <p className="lease-confirm-alert" role="alert">잔액을 불러오지 못했습니다. 차감 후 내역에서 확인해 주세요.</p>}
      {/* 조기 반납이 열리기 전에는 이 줄이 "되돌릴 수 없다" 는 뜻으로 읽혔다(S15P21A604-590).
          이제 되돌릴 수는 있고 코인만 안 돌아온다 — 그 차이를 말한다 (S15P21A604-735, GitLab #199) */}
      <p className="lease-confirm-note">
        만료 전에 반납할 수 있지만 <strong>차감한 코인은 돌려받지 못합니다.</strong> 다시 임대하려면 {LEASE_COIN_COST}코인을 또 냅니다.
      </p>
      <div className="lease-confirm-actions">
        <button type="button" className="sc-btn" onClick={onCancel} disabled={pending}>
          취소
        </button>
        <button type="button" className="sc-btn sc-btn-primary" onClick={onConfirm} disabled={pending}>
          {pending ? '요청 중...' : `${LEASE_COIN_COST}코인 차감하고 임대`}
        </button>
      </div>
    </dialog>
  );
}
