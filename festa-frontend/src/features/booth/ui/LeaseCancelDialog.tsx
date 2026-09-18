// 부스 반납 확인 모달 (S15P21A604-753, GitLab #199) — 임대 확인 모달과 같은 자리의 반대 동작이다.
//
// 임대와 한 컴포넌트로 합치지 않는다. 말하는 것이 정반대이고(차감 안내 vs 종료 안내) 공유할 것은
// <dialog> 상용구와 표면 스타일뿐이라, 합치면 분기만 늘고 문구가 서로를 오염시킨다.
//
// 말하는 것은 셋뿐이다 — **무엇을 반납하는가 / 지금 무슨 일이 일어나는가 / 돈은 어떻게 되는가**.
// Booth Studio 폐기로 배치·Draft 설명은 사실이 아니게 됐고, 남아 있던 보존 안내는 결정에 쓰이지
// 않으면서 모달만 길게 만들었다 (2026-09-17).
import { useEffect, useRef } from 'react';
import './leaseConfirmDialog.css';

interface Props {
  /** BE 부스명(GET /booths/mine 의 name). 슬롯 코드 같은 내부 식별자는 사용자에게 보이지 않는다 */
  boothName: string | null;
  /** 실제 차감된 코인(lease.chargedCoin) — 상수가 아니라 이 임대가 낸 값이다 */
  coin: number;
  pending: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function LeaseCancelDialog({ boothName, coin, pending, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  // showModal() 로만 연다 — open 속성으로 열면 Esc 닫기·포커스 가둠·backdrop 셋이 빠진다.
  //
  // 연 뒤 포커스를 '취소' 로 옮긴다. showModal() 은 DOM 순서상 첫 포커스 요소를 잡는데 그것이
  // 파괴적인 '반납' 이라, 그대로 두면 Enter 한 번에 반납이 실행된다.
  useEffect(() => {
    ref.current?.showModal();
    cancelRef.current?.focus();
  }, []);

  return (
    <dialog
      ref={ref}
      className="lease-confirm lease-cancel"
      onCancel={onCancel}
      aria-labelledby="lease-cancel-title"
    >
      <h2 id="lease-cancel-title" className="lease-cancel-title">부스를 반납할까요?</h2>
      {/* 반납 대상과 즉시 일어나는 일을 한 덩어리로 — 이 둘이 결정의 전부다 */}
      <div className="lease-cancel-target">
        <strong className="lease-cancel-booth">{boothName ?? '현재 부스'}</strong>
        <span className="lease-cancel-effect">반납 즉시 이용이 종료됩니다.</span>
      </div>
      <p className="lease-cancel-warn" role="alert">
        <span aria-hidden="true">⚠</span> 사용한 {coin}코인은 환불되지 않습니다.
      </p>
      {/* 순서는 반납 → 취소지만 포커스는 취소에 있다(위 effect) */}
      <div className="lease-cancel-actions">
        <button type="button" className="sc-btn lease-cancel-danger" onClick={onConfirm} disabled={pending}>
          {pending ? '반납 중...' : '반납'}
        </button>
        <button type="button" className="sc-btn" ref={cancelRef} onClick={onCancel} disabled={pending}>
          취소
        </button>
      </div>
    </dialog>
  );
}
