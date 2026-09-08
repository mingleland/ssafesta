// 게스트/비로그인 진입 화면 — DEV ONLY, AiChatOverlay(features/ai)와 완전히 별개 구현이다.
// 목업(Overlay Family)을 재사용하지 않는다 — 이 화면 자체가 독립 화면이길 원해서(요청).
import { useLocation, useNavigate } from 'react-router-dom';
import { saveReturnTo } from '../../features/auth/model/returnTo';

export function DevAiChatGuestGate({ onExit }: { onExit: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();

  function goLogin() {
    saveReturnTo(location.pathname + location.search + location.hash);
    navigate('/login');
  }

  return (
    <div className="dac-screen">
      <button type="button" className="dac-back" onClick={onExit}>
        ← 나가기
      </button>
      <div className="dac-gate">
        <span className="dac-gate-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="4" y="10" width="16" height="10" rx="2" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
          </svg>
        </span>
        <strong className="dac-gate-title">로그인이 필요합니다</strong>
        <p className="dac-gate-copy">AI 직원과의 대화는 소셜 로그인 회원만 이용할 수 있어요.</p>
        <button type="button" className="dac-gate-cta" onClick={goLogin}>
          로그인하러 가기
        </button>
      </div>
    </div>
  );
}
