// @vitest-environment jsdom
// OAuth 시작 URL 이 지금 이 FE 의 origin 을 `return` 으로 싣는다 (S15P21A604-649, GitLab #177).
//
// BE 는 이 값을 local deployment 에서 요청 자신의 origin 과 정확히 일치할 때만 세션에 보관했다가
// 로그인 완료 후 그 origin 의 /auth/callback 으로 돌려보낸다. FE 가 잠그는 것은 하나 — **값이
// 항상 `window.location.origin` 그대로**라는 것. 여기서 5173 을 하드코딩하거나 path 를 섞으면 BE 의
// 정확 일치 검사에서 조용히 떨어져 기존 frontend-base-url 로 튕긴다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../../../unity/host/warmup', () => ({ warmUpUnityAssets: () => {} }));

const realLocation = window.location;

function stubLocation(origin: string) {
  // jsdom 의 location 은 navigation 을 구현하지 않아 href 대입이 "Not implemented" 로 죽는다.
  // 읽기 전용 속성이라 delete 뒤 plain object 로 바꿔 끼운다 — href 대입만 기록하면 된다.
  const stub = { ...realLocation, origin, href: `${origin}/login`, pathname: '/login', search: '', hash: '' };
  Object.defineProperty(window, 'location', { configurable: true, writable: true, value: stub });
  return stub;
}

async function renderLogin() {
  const { LoginPage } = await import('../../LoginPage');
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.stubEnv('VITE_USE_MOCK', 'false');
  // base 두 개를 **빈 값으로 못박는다.** vitest 는 Vite 파이프라인이라 이 저장소 밖의 `.env` 를
  // 그대로 읽는데, 옛 `.env.example` 이 "로컬에서는 http://localhost:8080 을 넣는다" 고 권했다.
  // 그 값을 가진 머신에서는 아래 `url.origin` 단정이 8080 을 만나 깨진다 — 결과가 환경에 달리면
  // 이 테스트가 잠그려는 계약이 잠기지 않는다.
  vi.stubEnv('VITE_AUTH_BASE_URL', '');
  vi.stubEnv('VITE_API_BASE_URL', '');
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
});

describe.each([
  ['http://localhost:5173'],
  ['http://localhost:5175'],
  // **`localhost` 로만 연다.** `127.0.0.1` 로 시작하면 JSESSIONID(host-only)가 그 호스트에 붙고,
  // provider 콜백은 등록값인 `localhost:8080` 으로 돌아와 세션이 실리지 않는다 — Spring 이
  // `authorization_request_not_found` 로 떨어진다. 쿠키 host 비교는 포트만 무관하고 호스트는
  // 정확 일치다(`127.0.0.1` ≠ `localhost`). 포트만 다른 경우를 잠그는 것이 이 표의 목적이다.
  ['http://localhost:5188'],
])('OAuth 시작 origin 전달 — %s', (origin) => {
  it('Google 버튼이 /api/v1/auth/oauth/google?return=<origin> 으로 이동한다', async () => {
    const loc = stubLocation(origin);
    await renderLogin();

    fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }));

    const url = new URL(loc.href);
    expect(url.pathname).toBe('/api/v1/auth/oauth/google');
    expect(url.searchParams.get('return')).toBe(origin);
    // authBaseUrl 이 비어 있으면 FE 오리진 = 게이트웨이 경로다 — 8080 직행이 아니다
    expect(url.origin).toBe(origin);
  });
});

// 전체화면 의도 (S15P21A604-733). 로그인 클릭은 곧 제공자 도메인으로 나가므로 여기서 전체화면을
// 걸면 document 가 바뀌며 풀린다 — 의도만 남기고 '월드 입장' 클릭에서 쓴다.
describe('로그인 클릭의 전체화면 의도 (-733)', () => {
  it('클릭이 전체화면을 직접 부르지 않는다 — 걸어도 OAuth 이동에서 풀린다', async () => {
    const request = vi.fn();
    Object.defineProperty(document.documentElement, 'requestFullscreen', { value: request, configurable: true });
    stubLocation('http://localhost:5173');
    await renderLogin();

    fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }));

    expect(request).not.toHaveBeenCalled();
  });

  it('의도는 남긴다 — 월드 입장 신호가 이것을 소비한다', async () => {
    const { consumeFullscreenIntent } = await import('../../../../shared/ui/fullscreen');
    window.sessionStorage.clear();
    stubLocation('http://localhost:5173');
    await renderLogin();

    fireEvent.click(screen.getByRole('button', { name: 'Google 로그인' }));

    expect(consumeFullscreenIntent()).toBe(true);
  });
});
