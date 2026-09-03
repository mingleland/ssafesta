// Screen Local Baseline v0 — World 밖 React 화면의 공통 껍데기 (S15P21A604-406).
// 축제 세계를 배경으로 얇게 남겨 "게임 안의 화면"이라는 인상을 유지한다. 전역 Foundation 아님.
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import worldMockUrl from '../../../assets/festa/world/world-mock-background.png';
import './pageShell.css';

interface Props {
  title: string;
  subtitle?: string;
  /** 뒤로가기 목적지 — 없으면 버튼을 그리지 않는다 */
  backTo?: string;
  actions?: ReactNode;
  children: ReactNode;
}

export function PageShell({ title, subtitle, backTo, actions, children }: Props) {
  const navigate = useNavigate();
  return (
    <div className="festa-screen">
      <div className="festa-screen-bg" aria-hidden="true">
        <img src={worldMockUrl} alt="" />
      </div>
      <div className="festa-screen-inner">
        <header className="festa-screen-head">
          {backTo !== undefined && (
            <button type="button" className="festa-screen-back" onClick={() => navigate(backTo)} aria-label="뒤로">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M15 5l-7 7 7 7" />
              </svg>
            </button>
          )}
          <div className="festa-screen-titles">
            <h1 className="festa-screen-title">{title}</h1>
            {subtitle !== undefined && <span className="festa-screen-sub">{subtitle}</span>}
          </div>
          {actions !== undefined && <div className="festa-screen-actions">{actions}</div>}
        </header>
        {children}
      </div>
    </div>
  );
}

export function ScreenLoading({ label = '불러오는 중...' }: { label?: string }) {
  return (
    <div className="sc-state">
      <span className="sc-spinner" />
      {label}
    </div>
  );
}

export function ScreenError({ title, message, onRetry }: { title: string; message?: string; onRetry?: () => void }) {
  return (
    <div className="sc-state">
      <strong>{title}</strong>
      {message !== undefined && <span>{message}</span>}
      {onRetry !== undefined && (
        <button type="button" className="sc-btn" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  );
}

export function ScreenEmpty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="sc-state">
      <strong>{title}</strong>
      {hint !== undefined && <span>{hint}</span>}
    </div>
  );
}
