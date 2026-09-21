// AI 직원 문서 삭제 확인 모달 — 브라우저 기본 window.confirm 을 오버레이 톤(--sc-*)으로 대체한다.
//
// LeaseCancelDialog 과 같은 <dialog> 상용구·접근성 처리(showModal·Esc·backdrop·포커스 가둠)를
// 따른다. 임대 모달들과 한 컴포넌트로 합치지 않는 것도 같은 이유다 — 말하는 것이 다르고 공유할
// 것은 표면 스타일뿐이다. 이 모달은 AI 탭 JSX 안(.festa-overlay 하위)에 그려지므로 --sc-* 토큰이
// DOM 트리로 상속돼 닿는다 (임대 모달과 달리 pageShell.css 등록이 필요 없다).
import { useEffect, useRef } from 'react';
import './documentDeleteDialog.css';

interface Props {
  fileName: string;
  pending: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function DocumentDeleteDialog({ fileName, pending, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  // showModal() 로만 연다 — open 속성으로 열면 Esc 닫기·포커스 가둠·backdrop 셋이 빠진다.
  // 연 뒤 포커스를 '취소' 로 옮긴다 — DOM 순서상 첫 요소인 파괴적 '삭제' 에 Enter 가 꽂히지 않게.
  useEffect(() => {
    ref.current?.showModal();
    cancelRef.current?.focus();
  }, []);

  return (
    <dialog ref={ref} className="doc-delete" onCancel={onCancel} aria-labelledby="doc-delete-title">
      <h2 id="doc-delete-title" className="doc-delete-title">문서를 삭제할까요?</h2>
      <div className="doc-delete-target">
        <strong className="doc-delete-name">{fileName}</strong>
        <span className="doc-delete-effect">삭제 즉시 AI 직원의 답변 근거에서 제외됩니다.</span>
      </div>
      <p className="doc-delete-warn" role="alert">
        <span aria-hidden="true">⚠</span> 되돌릴 수 없습니다.
      </p>
      {/* 순서는 삭제 → 취소지만 포커스는 취소에 있다(위 effect) */}
      <div className="doc-delete-actions">
        <button type="button" className="sc-btn doc-delete-danger" onClick={onConfirm} disabled={pending}>
          {pending ? '삭제 중...' : '삭제'}
        </button>
        <button type="button" className="sc-btn" ref={cancelRef} onClick={onCancel} disabled={pending}>
          취소
        </button>
      </div>
    </dialog>
  );
}
