// client.ts의 setUnauthorizedHandler 등록 지점에 주입하는 실 로직(T011, plan.md §401 처리).
// member: refresh 1회 → 성공 시 client.ts가 원요청을 재시도, 실패 시 세션 클리어 + 재로그인 안내(FR-020b).
// guest: 재발급 시도 없이 즉시 게스트 재입장 안내(FR-009a — 자동 재발급 금지). 인터셉트는 client.ts가
// skipAuthRetry로 상한 1회를 보장하므로 여기서는 재시도 루프를 만들지 않는다.

import { setUnauthorizedHandler } from '../../../shared/api/client';
import { authApi } from '../../../entities/auth/api.select';
import { getSessionSnapshot, setMemberSession, clearSession } from './session';

let refreshInFlight: Promise<boolean> | null = null;

async function attemptRefresh(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      // 성공 판정은 "예외 없음"이다 — BE GuestTokenResponse 에는 status 가 없고, 실패는
      // mock(apiError throw)도 real(4xx → api() throw)도 예외로 알린다.
      const result = await authApi.refresh();
      setMemberSession(result.accessToken, result.expiresAt);
      return true;
    } catch {
      return false;
    }
  })();
  try {
    return await refreshInFlight;
  } finally {
    refreshInFlight = null;
  }
}

export async function handleUnauthorized(): Promise<boolean> {
  const { kind } = getSessionSnapshot();
  if (kind === 'member') {
    const recovered = await attemptRefresh();
    if (recovered) return true;
    clearSession('session-expired');
    return false;
  }
  if (kind === 'guest') {
    clearSession('guest-reentry-required');
    return false;
  }
  return false;
}

export function installUnauthorizedHandler(): void {
  setUnauthorizedHandler(handleUnauthorized);
}
