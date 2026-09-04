// 로그아웃 1동작 — 이전에는 RequireAuth 의 AuthHeader 안에 있었다(D-08 로 가드에서 UI 를 뺐다).
// 소비자는 ESC Game Menu 다. 서버 로그아웃이 실패해도 클라이언트 세션은 정리한다 —
// 남은 서버 상태 불일치는 다음 요청의 401 로 자연 정리된다.
import { authApi } from '../../../entities/auth/api.select';
import { clearSession } from './session';

export async function logout(): Promise<void> {
  try {
    await authApi.logout();
  } catch {
    // 서버측 실패는 삼키되 클라이언트 정리는 반드시 한다
  }
  clearSession();
}
