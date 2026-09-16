// client.ts의 setUnauthorizedHandler 등록 지점에 주입하는 실 로직(T011, plan.md §401 처리).
// member: refresh 1회 → 성공 시 client.ts가 원요청을 재시도, 실패 시 세션 클리어 + 재로그인 안내(FR-020b).
// guest: 재발급 시도 없이 즉시 게스트 재입장 안내(FR-009a — 자동 재발급 금지). 인터셉트는 client.ts가
// skipAuthRetry로 상한 1회를 보장하므로 여기서는 재시도 루프를 만들지 않는다.

import { getAccessToken, isApiError, setUnauthorizedHandler } from '../../../shared/api/client';
import { authApi } from '../../../entities/auth/api.select';
import { getSessionSnapshot, setMemberSession, clearSession } from './session';

let refreshInFlight: Promise<boolean> | null = null;

/**
 * refresh 가 왜 실패했는지 콘솔에 남긴다 (S15P21A604-819, GitLab #211).
 *
 * 여기서 오류를 통째로 버리고 있어서, 실제 강제 로그아웃이 났을 때 `INVALID_MEMBER_TOKEN`
 * (쿠키가 없거나 서버에 키가 없다)과 `REFRESH_TOKEN_ROTATED`(회전 경합)를 사후에 가를 방법이
 * 없었다. 화면에는 `session-expired` 안내만 남고 원인은 사라진다 — 실패를 조용히 삼키지
 * 않는다(T-24).
 *
 * `requestId` 를 함께 적는 이유는 그 값 하나로 서버 로그(S15P21A604-816)와 맞붙기 때문이다.
 *
 * **토큰 값은 남기지 않는다**(헌법 13조·15조). `hasAccessToken` 은 있었는지만 말한다 — 401 이
 * "AT 가 거절됐다" 인지 "AT 를 아예 안 실었다" 인지 지금 로그로 갈리지 않아서다. `getAccessToken`
 * 은 FE 내부 read boundary 로만 쓰고 Unity 로 넘기지 않는다(client.ts TODO(013a-AT)).
 */
function logRefreshFailure(stage: '최초' | '회전 재시도', error: unknown): void {
  const detail = isApiError(error)
    ? `${error.code}${error.status === undefined ? '' : ` (${error.status})`}: ${error.message}` +
      (error.requestId === undefined ? '' : ` requestId=${error.requestId}`)
    : error instanceof Error
      ? error.message
      : String(error);
  console.warn(
    `[auth] refresh 실패 (${stage}) — ${detail} · hasAccessToken=${getAccessToken() !== null}`,
  );
}

/**
 * 다른 탭이 방금 회전시킨 것뿐이다 (S15P21A604-812, GitLab #198).
 *
 * 탭이 둘이면 AT 만료가 동시에 와 둘 다 같은 refresh_token 으로 refresh 를 부른다. 하나가
 * 이기고 쿠키를 회전시키면 진 쪽은 옛 토큰을 들고 있어 **재사용 감지**에 걸렸다 — 그래서
 * 탭을 하나 더 여는 것만으로 세션이 통째로 끊겼다.
 *
 * BE 가 S15P21A604-764 로 30초 유예를 넣고 이 경우에만 전용 코드를 준다. 계보는 현행이고
 * 쿠키에는 **이미 새 토큰이 들어 있으므로** 한 번 더 부르면 그 값으로 성공한다.
 *
 * 진짜 계보 폐기(다른 브라우저 새 로그인)는 이 코드가 아니라서 여기 걸리지 않는다.
 */
const ROTATED = 'REFRESH_TOKEN_ROTATED';

async function attemptRefresh(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      // 성공 판정은 "예외 없음"이다 — BE GuestTokenResponse 에는 status 가 없고, 실패는
      // mock(apiError throw)도 real(4xx → api() throw)도 예외로 알린다.
      const result = await authApi.refresh();
      setMemberSession(result.accessToken, result.expiresAt);
      return true;
    } catch (error) {
      // 회전 경합이면 한 번만 더 본다. 두 번째도 같은 코드면 유예를 넘겼거나 계보가 정말
      // 끊긴 것이라 더 돌지 않는다 — 원인이 경합이라 1회로 충분하고, 루프는 만료된 세션에
      // 대고 무한히 두드리는 길이 된다.
      if (!isApiError(error) || error.code !== ROTATED) {
        logRefreshFailure('최초', error);
        return false;
      }
      try {
        const retried = await authApi.refresh();
        setMemberSession(retried.accessToken, retried.expiresAt);
        return true;
      } catch (retryError) {
        logRefreshFailure('회전 재시도', retryError);
        return false;
      }
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
