// 교환·응모 전에 받는 자 정보를 받는 확인 모달(S15P21A604-842 후속). 코인 차감 자체는 여기서
// 일어나지 않는다 — 확인을 누르면 부모가 그 정보를 들고 실제 mutation을 부른다.
import { useEffect, useRef, useState } from 'react';
import { CAMPUS_OPTIONS, isRecipientComplete } from '../../../shared/contracts/purchaseRecipient';
import type { PurchaseRecipient } from '../../../shared/contracts/purchaseRecipient';
import './purchaseRecipientForm.css';

interface Props {
  actionLabel: string;
  itemName: string;
  priceLabel: string;
  /** 구매/응모별로 다른 안내 한 줄 — "MM으로 기프티콘을 보내드립니다." 계열(S15P21A604-842 후속) */
  deliveryNote: string;
  /** 지정하면 캠퍼스가 이 값으로 고정되고 선택을 막는다 — 말랑이(서울캠퍼스 한정) 전용(S15P21A604-842 후속) */
  lockedCampus?: PurchaseRecipient['campus'];
  pending: boolean;
  onConfirm: (recipient: PurchaseRecipient) => void;
  onCancel: () => void;
}

export function PurchaseRecipientForm({
  actionLabel,
  itemName,
  priceLabel,
  deliveryNote,
  lockedCampus,
  pending,
  onConfirm,
  onCancel,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const [campus, setCampus] = useState<PurchaseRecipient['campus']>(lockedCampus ?? CAMPUS_OPTIONS[0]);
  const [teamName, setTeamName] = useState('');
  const [recipientName, setRecipientName] = useState('');

  useEffect(() => {
    ref.current?.showModal();
  }, []);

  const recipient = { campus, teamName, recipientName };
  const canSubmit = isRecipientComplete(recipient) && !pending;

  return (
    <dialog ref={ref} className="ov-recipient" onClose={onCancel} aria-labelledby="ov-recipient-title">
      <h2 id="ov-recipient-title" className="ov-recipient-title">{itemName} {actionLabel}</h2>
      <p className="ov-recipient-lead">{priceLabel} — {deliveryNote}</p>

      <label className="ov-recipient-field" htmlFor="recipient-campus">
        <span>캠퍼스</span>
        <select
          id="recipient-campus"
          value={campus}
          onChange={(e) => setCampus(e.target.value as PurchaseRecipient['campus'])}
          disabled={pending || lockedCampus !== undefined}
        >
          {CAMPUS_OPTIONS.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
      </label>

      <label className="ov-recipient-field" htmlFor="recipient-team">
        <span>조 이름</span>
        <input
          id="recipient-team"
          type="text"
          value={teamName}
          maxLength={50}
          placeholder="예: A101"
          onChange={(e) => setTeamName(e.target.value)}
          disabled={pending}
        />
      </label>

      <label className="ov-recipient-field" htmlFor="recipient-name">
        <span>이름</span>
        <input
          id="recipient-name"
          type="text"
          value={recipientName}
          maxLength={50}
          placeholder="예: 홍길동"
          onChange={(e) => setRecipientName(e.target.value)}
          disabled={pending}
        />
      </label>

      <div className="ov-recipient-actions">
        <button type="button" className="ov-btn" onClick={onCancel} disabled={pending}>
          취소
        </button>
        <button
          type="button"
          className="ov-btn ov-btn-primary"
          disabled={!canSubmit}
          onClick={() => {
            if (isRecipientComplete(recipient)) onConfirm(recipient);
          }}
        >
          {pending ? '처리 중...' : `${actionLabel} 확정`}
        </button>
      </div>
    </dialog>
  );
}
