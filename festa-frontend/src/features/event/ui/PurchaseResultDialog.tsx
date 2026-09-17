// 교환·응모 완료 팝업(S15P21A604-842) — 토스트는 스치듯 사라져 "정말 됐나" 가 남는다.
// 코인이 실제로 빠진 행동 뒤에는 그 자리에서 확인하고 닫는 팝업을 하나 세운다.
import { useEffect, useRef } from 'react';
import './purchaseResultDialog.css';

interface Props {
  title: string;
  itemName: string;
  coinSpent: number;
  /** 응모 완료일 때 추첨 시각 등 덧붙일 한 줄. 없으면 안 그린다 */
  note?: string;
  onClose: () => void;
}

export function PurchaseResultDialog({ title, itemName, coinSpent, note, onClose }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  // <dialog>를 쓰는 이유는 LeaseConfirmDialog와 같다 — Esc·포커스 가둠·backdrop을 직접 구현하지 않는다.
  useEffect(() => {
    ref.current?.showModal();
  }, []);

  return (
    <dialog ref={ref} className="ov-result" onClose={onClose} aria-labelledby="ov-result-title">
      <h2 id="ov-result-title" className="ov-result-title">{title}</h2>
      <p className="ov-result-lead">
        <strong>{itemName}</strong>
      </p>
      <span className="ov-chip ov-chip-coin ov-result-chip">{coinSpent.toLocaleString()} C 사용</span>
      {note !== undefined && <p className="ov-result-note">{note}</p>}
      <div className="ov-result-actions">
        {/* dialog.close()를 부르지 않는다 — 부모가 상태를 지워 이 컴포넌트를 언마운트하는 것만으로
            충분하다. Esc로 닫히는 경로는 네이티브 'close' 이벤트(위 onClose)가 따로 받는다 */}
        <button type="button" className="ov-btn ov-btn-primary" onClick={onClose}>
          확인
        </button>
      </div>
    </dialog>
  );
}
