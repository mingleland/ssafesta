// OverlayFrame — World 위에 뜨는 React Overlay 의 공통 골격 (S15P21A604-406).
// Project 를 앵커로 만들고 LAPTOP·Survey·Consultation·AI·GAME 이 같은 Frame 을 재사용한다.
// 지위: Overlay Local Baseline v0 — 전역 Foundation SSOT 아님.
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import './overlayFrame.css';

export type OverlaySize = 's' | 'm' | 'l' | 'xl';

interface Props {
  title: string;
  subtitle?: string;
  size?: OverlaySize;
  icon?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  status?: ReactNode;
  onClose: () => void;
}

export function OverlayFrame({ title, subtitle, size = 'l', icon, children, footer, status, onClose }: Props) {
  const frameRef = useRef<HTMLDivElement>(null);

  // Esc 로 닫는다. Unity Input Lock 계약(G-8-2)은 Unity 합의 대기 — 여기서는 화면 닫기와 focus 소유권만.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // focus 소유권 (S15P21A604-428, G-8-1). 열릴 때 프레임이 가져가고, 닫힐 때 돌려준다.
  // 돌려주지 않으면 focus 가 body 에 남고, Unity 6 WebGL 은 키보드 이벤트를 canvas 타깃으로 받으므로
  // 오버레이를 닫은 뒤 WASD·F 가 월드로 가지 않는다. 월드에서 F 로 연 경우 이전 요소가 곧 canvas 다.
  useEffect(() => {
    const previous = document.activeElement;
    frameRef.current?.focus();
    return () => {
      // 이전 요소가 아직 문서에 있고 focus 를 받을 수 있으면 그쪽이 우선 — a11y 기본 동작이다.
      if (previous instanceof HTMLElement && previous.isConnected && previous !== document.body) {
        previous.focus();
        return;
      }
      // dev 트리거처럼 body 에서 연 경우엔 돌려줄 곳이 없다 — 월드 화면이면 canvas 로 돌린다.
      // canvas 가 없는 화면(Game Studio 등)에서는 아무것도 하지 않는다.
      document.querySelector('canvas')?.focus();
    };
  }, []);

  return (
    <div className="festa-overlay" role="presentation">
      {/* 배경 클릭으로 닫는다 — 월드는 계속 보인다(이미지에 dim 을 굽지 않는 이유) */}
      <button type="button" className="festa-overlay-dim" aria-label="닫기" onClick={onClose} />
      <section
        ref={frameRef}
        className="festa-overlay-frame"
        data-size={size}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
      >
        <header className="festa-overlay-head">
          {icon && <span className="festa-overlay-badge">{icon}</span>}
          <span className="festa-overlay-titles">
            <h2 className="festa-overlay-title">{title}</h2>
            {subtitle !== undefined && <span className="festa-overlay-subtitle">{subtitle}</span>}
          </span>
          <button type="button" className="festa-overlay-close" onClick={onClose} aria-label="닫기">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        <div className="festa-overlay-body">{children}</div>

        {(footer !== undefined || status !== undefined) && (
          <footer className="festa-overlay-foot">
            <span className="festa-overlay-foot-status">{status}</span>
            {footer}
          </footer>
        )}
      </section>
    </div>
  );
}

/** 로딩·빈 상태·오류의 공통 표현 — Overlay Family 전체가 같은 모양을 쓴다 */
export function OverlayLoading({ label = '불러오는 중...' }: { label?: string }) {
  return (
    <div className="festa-overlay-state">
      <span className="festa-overlay-spinner" />
      {label}
    </div>
  );
}

export function OverlayEmpty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="festa-overlay-state">
      <strong>{title}</strong>
      {hint}
    </div>
  );
}

export function OverlayError({ title, message, onRetry }: { title: string; message?: string; onRetry?: () => void }) {
  return (
    <div className="festa-overlay-state">
      <strong>{title}</strong>
      {message}
      {onRetry && (
        <button type="button" className="ov-btn" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  );
}
