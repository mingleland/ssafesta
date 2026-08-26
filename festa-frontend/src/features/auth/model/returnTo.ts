// 로그인 후 원래 목적지 복귀(returnTo, G-2). RequireAuth가 anonymous를 /login으로 보내기 직전
// 원래 경로를 저장하고, 로그인 완료 지점(CallbackPage 2곳·LoginPage 게스트 입장)이 소비한다.
// plan.md §라우트 설계: "returnTo 보존은 범위 밖 — 필요해지면 후속"이라 명시했던 그 후속.
//
// 저장 위치: sessionStorage. 실 OAuth는 전체 페이지 이동(window.location.href, plan.md §라우트
// 설계)이라 SPA 메모리(모듈 스코프 state, session.ts와 동일 패턴)는 provider 왕복 사이에 살아남지
// 못한다 — mock의 handoff 저장(entities/auth/api.mock.ts)과 달리 여기서는 sessionStorage가
// 백업이 아니라 유일한 저장소다. vitest(node 환경)엔 sessionStorage가 없어 try/catch로 미가용을
// 흡수한다(api.mock.ts와 동일 패턴) — 미가용 시 복귀 없이 기본 목적지로 떨어진다(크래시보다 낫다).

const STORAGE_KEY = 'festa-auth-return-to';
const DEFAULT_RETURN_TO = '/app/home';

// open redirect 방어(S-20260823-80) — 앱 내부 경로만 허용한다. 정규식 대신 WHATWG URL 파서로
// 브라우저가 실제로 그 문자열을 어떻게 해석할지 그대로 재현한다 — `/\evil.com`처럼 브라우저가
// 백슬래시를 슬래시로 정규화해 protocol-relative(`//evil.com`)가 되는 트릭까지 origin 비교
// 하나로 함께 걸러진다(정규식 블랙리스트로는 이런 정규화 우회를 놓치기 쉽다).
export function isSafeReturnTo(path: string): boolean {
  if (typeof path !== 'string' || path === '' || !path.startsWith('/')) return false;
  const base = 'http://festa-internal.invalid';
  try {
    return new URL(path, base).origin === base;
  } catch {
    return false;
  }
}

export function saveReturnTo(path: string): void {
  if (!isSafeReturnTo(path)) return;
  try {
    sessionStorage.setItem(STORAGE_KEY, path);
  } catch {
    // sessionStorage 미가용(프라이빗 모드 등) — 복귀는 포기하고 기본 목적지로 떨어진다
  }
}

// 1회 소비 — 읽자마자 지운다(다음 방문에 stale 값이 새지 않게). 손상되었거나 안전하지 않은 값,
// 또는 애초에 저장된 값이 없으면 기본 목적지로 떨어진다.
export function consumeReturnTo(): string {
  try {
    const stored = sessionStorage.getItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
    if (stored && isSafeReturnTo(stored)) return stored;
  } catch {
    // sessionStorage 미가용 — 기본 목적지로
  }
  return DEFAULT_RETURN_TO;
}
