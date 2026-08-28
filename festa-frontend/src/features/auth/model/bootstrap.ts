// 앱 시작 시 1회 — 401 인터셉트를 등록하고, 회원이면 refresh를 조용히 시도해 새로고침 후에도
// 세션을 복원한다(T012, plan.md §새로고침 복원 — client.ts:33 주석의 spec 001 이관분).
// 게스트는 복원하지 않는다(FR-009a) — RT cookie 존재를 FE가 알 수 없어(HttpOnly) 실패하면 조용히
// anonymous로 둔다. 실제로는 mock/real 모두 이전에 member였을 때만 refresh가 성공하도록 만들어져
// 있어 게스트는 자연히 복원되지 않는다.

import { authApi } from '../../../entities/auth/api.select';
import { markBootstrapped, setMemberSession } from './session';
import { installUnauthorizedHandler } from './unauthorizedHandler';

let started = false;

export async function bootstrapAuth(): Promise<void> {
  if (started) return; // StrictMode 이중 mount 방어 — 페이지를 벗어나면 어차피 전체 리로드로 초기화된다
  started = true;
  installUnauthorizedHandler();
  try {
    // 성공 판정은 "예외 없음"이다(BE GuestTokenResponse 에 status 가 없다).
    // 게스트·비로그인은 refresh_token 쿠키가 없어 여기서 실패하고 조용히 anonymous 로 남는다.
    const result = await authApi.refresh();
    setMemberSession(result.accessToken, result.expiresAt);
  } catch {
    // RT 부재·만료·게스트 등 — 조용히 anonymous로 남는다
  } finally {
    // 성패와 무관하게 여기서부터 가드 판정 유효 — 이전엔 refresh가 끝나기 전에 가드가
    // 초기 anonymous로 /login redirect를 확정해 새로고침 복원이 항상 졌다(quickstart §6 실측).
    markBootstrapped();
  }
}

// 테스트 전용
export function __resetBootstrapForTests(): void {
  started = false;
}
