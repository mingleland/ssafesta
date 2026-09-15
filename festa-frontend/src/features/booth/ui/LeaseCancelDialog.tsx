// 부스 반납 확인 모달 (S15P21A604-753, GitLab #199) — 임대 확인 모달과 같은 자리의 반대 동작이다.
//
// 임대와 한 컴포넌트로 합치지 않는다. 말하는 것이 정반대이고(차감 안내 vs 보존 안내) 공유할 것은
// <dialog> 상용구뿐이라, 합치면 분기만 늘고 문구가 서로를 오염시킨다.
import { useEffect, useRef } from 'react';
import { LEASE_COIN_COST } from '../../../entities/booth/types';
import './leaseConfirmDialog.css';

interface Props {
  slotCode: string | null;
  pending: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function LeaseCancelDialog({ slotCode, pending, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  // showModal() 로만 연다 — open 속성으로 열면 Esc 닫기·포커스 가둠·backdrop 셋이 빠진다.
  useEffect(() => {
    ref.current?.showModal();
  }, []);

  return (
    <dialog ref={ref} className="lease-confirm" onCancel={onCancel} aria-labelledby="lease-cancel-title">
      <h2 id="lease-cancel-title" className="lease-confirm-title">부스 반납</h2>
      <p className="lease-confirm-lead">
        <strong>{slotCode ?? '이 슬롯'}</strong> 의 임대를 지금 끝내고 자리를 비웁니다.
      </p>
      {/* 사용자가 가장 걱정하는 둘을 먼저 말한다 — 코인은 안 돌아오고, 만들어 둔 것은 남는다 */}
      <p className="lease-confirm-alert" role="alert">
        차감한 {LEASE_COIN_COST}코인은 <strong>돌려받지 못합니다.</strong> 다시 임대하려면 같은 금액을 또 냅니다.
      </p>
      <p className="lease-confirm-note">
        배치·AI 직원·문서·설문·프로젝트는 <strong>그대로 남습니다.</strong> 다시 임대하면 만료 후 재임대와 같이
        Draft 부터 시작합니다.
      </p>
      <div className="lease-confirm-actions">
        <button type="button" className="sc-btn" onClick={onCancel} disabled={pending}>
          취소
        </button>
        <button type="button" className="sc-btn sc-btn-primary" onClick={onConfirm} disabled={pending}>
          {pending ? '반납 중...' : '반납하기'}
        </button>
      </div>
    </dialog>
  );
}
