// Toast 렌더 (S15P21A604-465). AppProviders 에 마운트한다 — 라우터 **밖**이라
// / → /login → /app/world 전환에도 살아 있고, 어느 화면에서 띄우든 같은 자리에 뜬다.
//
// 이 컴포넌트는 자기가 어느 화면에 있는지 모른다. 우상단에는 화면마다 다른 주민이
// 있어(로그인은 ScreenControls, 월드는 Consultation Quick Access) 시작 높이가 다른데,
// 그 차이는 각 화면이 `--festa-toast-top` 을 덮어써서 흡수한다.
import { dismissToast, useToasts } from './toastStore';
import './toast.css';

export function ToastHost() {
  const toasts = useToasts();
  if (toasts.length === 0) return null;

  return (
    <div className="toast-host" role="region" aria-label="알림">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`} role="alert">
          <span className="toast-message">{toast.message}</span>
          <button
            type="button"
            className="toast-close"
            aria-label="알림 닫기"
            onClick={() => dismissToast(toast.id)}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
      ))}
    </div>
  );
}
