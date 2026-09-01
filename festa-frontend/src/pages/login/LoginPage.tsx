// /login이 마운트하는 화면(T006·T009) — Google/Kakao 버튼 + 게스트 입장 + 실패 안내 영역(FR-007).
// 소셜 버튼은 SPA fetch가 아니라 전체 페이지 이동이다(plan.md §라우트 설계) — provider 인증·backend
// callback·302 redirect가 브라우저 내비게이션으로 일어나기 때문. mock 모드만 예외적으로 provider
// 왕복을 SPA 내비게이션으로 흉내낸다(plan.md §Mock 전략).
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { isApiError } from '../../shared/api/client';
import { authApi } from '../../entities/auth/api.select';
import { mockStartOAuth } from '../../entities/auth/api.mock';
import { setGuestSession, useSession } from '../../features/auth/model/session';
import { consumeReturnTo } from '../../features/auth/model/returnTo';
import { apiBaseUrl } from '../../shared/config/runtime';
import { oauthProviders, isConfiguredOAuth } from '../../entities/auth/providers';
import type { AuthProviderId } from '../../shared/contracts/auth';

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

export function LoginPage() {
  const navigate = useNavigate();
  const { notice } = useSession();
  const [guestError, setGuestError] = useState<string | null>(null);
  const [guestPending, setGuestPending] = useState(false);

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
    <div>
      <h1>로그인</h1>
      {notice === 'session-expired' && <p role="alert">세션이 종료되었습니다. 다시 로그인해 주세요.</p>}
      {notice === 'guest-reentry-required' && <p role="alert">게스트 이용 시간이 끝났습니다. 다시 입장해 주세요.</p>}

      {oauthProviders.map((provider) => (
        <button
          key={provider.id}
          type="button"
          disabled={provider.availability !== 'available'}
          onClick={() => startOAuth(provider.id)}
        >
          {provider.label}
        </button>
      ))}

      <button type="button" onClick={handleGuestEnter} disabled={guestPending}>
        게스트로 둘러보기
      </button>
      {guestError && <p role="alert">{guestError}</p>}
    </div>
  );
}
