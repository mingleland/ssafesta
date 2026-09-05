// /login이 마운트하는 화면(T006·T009) — Google/Kakao 버튼 + 게스트 입장 + 실패 안내 영역(FR-007).
// 소셜 버튼은 SPA fetch가 아니라 전체 페이지 이동이다(plan.md §라우트 설계) — provider 인증·backend
// callback·302 redirect가 브라우저 내비게이션으로 일어나기 때문. mock 모드만 예외적으로 provider
// 왕복을 SPA 내비게이션으로 흉내낸다(plan.md §Mock 전략).
// 표시 순서는 reference(login.png) 기준 SSAFY→Google→Kakao→게스트 — 데이터는 provider registry 가 정본이다.
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { isApiError } from '../../shared/api/client';
import { authApi } from '../../entities/auth/api.select';
import { mockStartOAuth } from '../../entities/auth/api.mock';
import { setGuestSession, useSession } from '../../features/auth/model/session';
import { consumeReturnTo } from '../../features/auth/model/returnTo';
import { apiBaseUrl } from '../../shared/config/runtime';
import { warmUpUnityAssets } from '../../unity/host/warmup';
import { authProviders, guestProvider, isConfiguredOAuth } from '../../entities/auth/providers';
import type { AuthProviderId, AuthProviderVM } from '../../shared/contracts/auth';
import loginBackgroundUrl from '../../assets/festa/backgrounds/login-background.png';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.png';
import './LoginPage.css';

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

// reference(login.png)의 버튼 순서. registry 에 없는 id 는 조용히 빠진다 — 별도 fake 배열을 만들지 않는다.
const OAUTH_DISPLAY_ORDER: readonly AuthProviderId[] = ['ssafy', 'google', 'kakao'];

function providerIcon(provider: AuthProviderVM) {
  switch (provider.id) {
    case 'ssafy':
      return <span className="login-btn-icon">S</span>;
    case 'google':
      return (
        <span className="login-btn-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M21.6 12.2c0-.7-.06-1.4-.18-2H12v3.9h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.4Z" fill="#4285F4" />
            <path d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22Z" fill="#34A853" />
            <path d="M6.4 14a6 6 0 0 1 0-3.9V7.5H3.1a10 10 0 0 0 0 9L6.4 14Z" fill="#FBBC05" />
            <path d="M12 6c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3.1 7.5L6.4 10c.8-2.3 3-4 5.6-4Z" fill="#EA4335" />
          </svg>
        </span>
      );
    case 'kakao':
      return (
        <span className="login-btn-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 3C6.9 3 3 6.2 3 10.1c0 2.5 1.6 4.6 4 5.9l-1 3.7c-.1.3.3.6.6.4l4.4-2.9c.3 0 .7.1 1 .1 5.1 0 9-3.2 9-7.2S17.1 3 12 3Z"
              fill="#1d1d1f"
            />
          </svg>
        </span>
      );
    default:
      return null;
  }
}

export function LoginPage() {
  const navigate = useNavigate();
  const { notice } = useSession();
  const [guestError, setGuestError] = useState<string | null>(null);
  const [guestPending, setGuestPending] = useState(false);

  // warm-up 2단계 (S15P21A604-430). 로그인 화면에 도달했다는 것은 월드 진입 의도가 드러난 것이라
  // framework 까지 넓힌다. 소셜 로그인은 전체 페이지 이동이라 그 순간 요청이 끊기지만, 받아 둔 만큼은
  // HTTP 캐시에 남아 복귀 후 다시 쓰인다. 게스트 입장은 SPA 이동이라 그대로 이어진다.
  useEffect(() => warmUpUnityAssets('intent'), []);

  const orderedOAuth = OAUTH_DISPLAY_ORDER.map((id) => authProviders.find((p) => p.id === id)).filter(
    (p): p is AuthProviderVM => p !== undefined,
  );

  function startOAuth(provider: AuthProviderId) {
    // not_configured(ssafy) 는 여기 도달해도 아무 것도 하지 않는다 — 실 OAuth 계약(-357) 전 발명 금지
    if (!isConfiguredOAuth(provider)) return;
    if (USE_MOCK) {
      mockStartOAuth(provider);
      navigate('/auth/callback');
      return;
    }
    window.location.href = `${apiBaseUrl()}/api/v1/auth/oauth/${provider}`;
  }

  async function handleGuestEnter() {
    setGuestPending(true);
    setGuestError(null);
    try {
      // 성공 판정은 "예외 없음"이다 — BE 게스트 응답에는 status 가 없다. 실패는 catch 로 온다.
      const result = await authApi.guestEnter();
      setGuestSession(result.accessToken, result.expiresAt);
      navigate(consumeReturnTo(), { replace: true });
    } catch (err) {
      // FR-007 — 원인 범주(서버 message)·재시도 방법(버튼 재클릭) 표시
      setGuestError(isApiError(err) ? err.message : '게스트 입장에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setGuestPending(false);
    }
  }

  return (
    <div className="login-root">
      <img className="login-bg" src={loginBackgroundUrl} alt="" />
      <img className="login-logo" src={ssafestaLogoUrl} alt="SSAFESTA" />
      <h1 className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
        로그인
      </h1>

      <div className="login-panel">
        {notice === 'session-expired' && (
          <p className="login-alert" role="alert">
            세션이 종료되었습니다. 다시 로그인해 주세요.
          </p>
        )}
        {notice === 'guest-reentry-required' && (
          <p className="login-alert" role="alert">
            게스트 이용 시간이 끝났습니다. 다시 입장해 주세요.
          </p>
        )}

        {orderedOAuth.map((provider) => (
          <button
            key={provider.id}
            type="button"
            className={`login-btn login-btn-${provider.id}`}
            disabled={provider.availability !== 'available'}
            onClick={() => startOAuth(provider.id)}
          >
            {providerIcon(provider)}
            <span className="login-btn-label">{provider.label}</span>
            {provider.availability === 'not_configured' && <span className="login-badge">준비 중</span>}
          </button>
        ))}

        <button type="button" className="login-btn login-btn-guest" onClick={handleGuestEnter} disabled={guestPending}>
          <span className="login-btn-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <circle cx="12" cy="8" r="4" />
              <path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5" />
            </svg>
          </span>
          <span className="login-btn-label">{guestProvider.label}</span>
        </button>
        {guestError && (
          <p className="login-alert" role="alert">
            {guestError}
          </p>
        )}
      </div>

      <footer className="login-footer">
        <div className="login-footer-group">
          <span>축제 안내</span>
          <span>이벤트</span>
          <span>고객센터</span>
        </div>
        <div>
          <span>개인정보처리방침</span>
          <span className="login-footer-sep">|</span>
          <span>이용약관</span>
        </div>
      </footer>
    </div>
  );
}
