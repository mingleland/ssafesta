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
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
});

describe.each([
  ['http://localhost:5173'],
  ['http://localhost:5175'],
  ['http://127.0.0.1:5188'],
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
