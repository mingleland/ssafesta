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
import { authBaseUrl } from '../../shared/config/runtime';
import { warmUpUnityAssets } from '../../unity/host/warmup';
import { ScreenControls } from '../../features/audio/ui/ScreenControls';
import { DevEntryButton } from '../../features/devEntry/ui/DevEntryButton';
import { showToast } from '../../shared/ui/toast/toastStore';
import { authProviders, guestProvider, isConfiguredOAuth } from '../../entities/auth/providers';
import type { AuthProviderId, AuthProviderVM } from '../../shared/contracts/auth';
// WebP 전환 (S15P21A604-733) — 배경 2.86MB→328KB, 로고 1.77MB→246KB. 원본 PNG 는 남겨 둔다.
import loginBackgroundUrl from '../../assets/festa/backgrounds/login-background.webp';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.webp';
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

/* 푸터 5항목(축제 안내·이벤트·고객센터·개인정보처리방침·이용약관)을 숨겼다 (S15P21A604-815).

   레퍼런스(login.png) 를 맞추려고 표시만 해 둔 것인데 다섯 개 다 갈 곳이 없다. 눌러도 아무 일이
   없는 것을 사용자가 먼저 발견하는 것보다 안 보이는 편이 낫다.

   지우지 않고 주석으로 두는 이유: 레퍼런스 대조 근거이고, 대상이 생기면 span 을 a 로 바꿔
   그대로 되살린다. CSS(.login-footer*)도 남겨 둔다.

const footerIcon = (path: string) => (
  <svg
    className="login-footer-icon"
    width="13"
    height="13"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d={path} />
  </svg>
);

const footerLinks = [
  { label: '축제 안내', icon: footerIcon('M12 3a9 9 0 100 18 9 9 0 000-18M12 8h.01M11 12h1v5h1') },
  { label: '이벤트', icon: footerIcon('M12 3l2.1 5.4L20 9.3l-4 3.9 1 5.8-5-2.7-5 2.7 1-5.8-4-3.9 5.9-.9z') },
  { label: '고객센터', icon: footerIcon('M11 4a7 7 0 100 14 7 7 0 000-14M20 21l-4.2-4.2') },
];
*/

export function LoginPage() {
  const navigate = useNavigate();
  const { notice } = useSession();
  const [guestPending, setGuestPending] = useState(false);

  // warm-up 2단계 (S15P21A604-430). 로그인 화면에 도달했다는 것은 월드 진입 의도가 드러난 것이라
  // framework 까지 넓힌다. 소셜 로그인은 전체 페이지 이동이라 그 순간 요청이 끊기지만, 받아 둔 만큼은
  // HTTP 캐시에 남아 복귀 후 다시 쓰인다. 게스트 입장은 SPA 이동이라 그대로 이어진다.
  useEffect(() => warmUpUnityAssets('intent'), []);

  // 진입 맥락 안내(세션 만료·게스트 재입장)를 패널 안에 두면 버튼이 아래로 밀린다 —
  // 낮은 화면에서는 그것만으로 푸터를 뚫는다. 알림은 레이아웃 밖으로 보낸다 (S15P21A604-465).
  useEffect(() => {
    if (notice === 'session-expired') showToast('세션이 종료되었습니다. 다시 로그인해 주세요.', 'info');
    if (notice === 'guest-reentry-required') showToast('게스트 이용 시간이 끝났습니다. 다시 입장해 주세요.', 'info');
  }, [notice]);

  const orderedOAuth = OAUTH_DISPLAY_ORDER.map((id) => authProviders.find((p) => p.id === id)).filter(
    (p): p is AuthProviderVM => p !== undefined,
  );

  function startOAuth(provider: AuthProviderId) {
    // 배선되지 않은 provider 는 여기서 멈춘다 — registry 가 유일한 판정자다(-495 이후 3종 모두 배선).
    if (!isConfiguredOAuth(provider)) return;
    if (USE_MOCK) {
      mockStartOAuth(provider);
      navigate('/auth/callback');
      return;
    }
    // 일반 API 와 다른 base 를 쓴다 — 인가 요청·provider 콜백·complete 가 같은 호스트여야
    // host-only 인 JSESSIONID·oauth_handoff 가 이어진다 (S15P21A604-564, entities/auth/api.ts).
    //
    // `return` 은 **지금 이 FE 가 어느 origin 인지**를 BE 에 알리는 값이다 (S15P21A604-649, #177).
    // BE 는 이 값을 그대로 믿지 않는다 — local deployment 에서 요청 자신의 origin 과 정확히 일치할
    // 때만 세션에 보관했다가 로그인 완료 후 그 origin 의 /auth/callback 으로 돌려보내고, 그 밖에는
    // 무시하고 기존 frontend-base-url 로 간다. 없으면 오늘과 동일하므로 어느 BE 에 붙여도 안전하다.
    const returnOrigin = encodeURIComponent(window.location.origin);
    window.location.href = `${authBaseUrl()}/api/v1/auth/oauth/${provider}?return=${returnOrigin}`;
  }

  async function handleGuestEnter() {
    setGuestPending(true);
    try {
      // 성공 판정은 "예외 없음"이다 — BE 게스트 응답에는 status 가 없다. 실패는 catch 로 온다.
      const result = await authApi.guestEnter();
      setGuestSession(result.accessToken, result.expiresAt);
      navigate(consumeReturnTo(), { replace: true });
    } catch (err) {
      // FR-007 — 원인 범주(서버 message)·재시도 방법(버튼 재클릭) 표시.
      // 토스트로 띄우되 포커스는 버튼에 남는다 — 읽고 그 자리에서 다시 누를 수 있다.
      showToast(
        isApiError(err) ? err.message : '게스트 입장에 실패했습니다. 잠시 후 다시 시도해 주세요.',
        'error',
      );
    } finally {
      setGuestPending(false);
    }
  }

  return (
    <div className="login-root">
      <ScreenControls />
      {/* 개발자 입장구 — 제품 로그인 버튼을 빌려 쓰지 않는다. 패널 밖이라 버튼 좌표를 밀지 않는다 */}
      <DevEntryButton />
      <link rel="preload" as="image" href={loginBackgroundUrl} fetchPriority="high" />
      <img className="login-bg" src={loginBackgroundUrl} alt="" width={1672} height={941} fetchPriority="high" decoding="async" />
      <img className="login-logo" src={ssafestaLogoUrl} alt="SSAFESTA" width={1986} height={792} decoding="async" />
      <h1 className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
        로그인
      </h1>

      <div className="login-panel">
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
      </div>

      {/* 링크 대상이 생기면 되살린다 (S15P21A604-815)
      <footer className="login-footer">
        <div className="login-footer-group">
          {footerLinks.map((link) => (
            <span key={link.label} className="login-footer-item">
              {link.icon}
              {link.label}
            </span>
          ))}
        </div>
        <div>
          <span>개인정보처리방침</span>
          <span className="login-footer-sep">|</span>
          <span>이용약관</span>
        </div>
      </footer>
      */}
    </div>
  );
}
