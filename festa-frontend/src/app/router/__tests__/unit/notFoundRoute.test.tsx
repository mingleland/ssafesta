// @vitest-environment jsdom
// 없는 주소는 제품 404 다 — React Router 기본 영어 화면이 사용자에게 닿지 않아야 한다.
// nginx 가 모든 주소에 index.html 을 200 으로 주므로 없는 주소 판정은 여기서만 할 수 있다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { routes } from '../../index';

afterEach(cleanup);

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
    </QueryClientProvider>,
  );
}

describe('없는 경로', () => {
  it('SSAFESTA 404 화면을 한글로 그린다', () => {
    renderAt('/nope-nope');
    expect(screen.getByText('페이지를 찾을 수 없습니다')).not.toBeNull();
    expect(screen.getByRole('button', { name: '홈으로 이동' })).not.toBeNull();
  });

  it('React Router 기본 오류 화면 문구가 남지 않는다', () => {
    renderAt('/app/없는하위경로/12');
    const text = document.body.textContent ?? '';
    expect(text).not.toContain('Unexpected Application Error');
    expect(text).not.toContain('Hey developer');
    expect(text).not.toContain('No route matches');
    expect(text).not.toContain('404 Not Found');
  });

  it('라우트 트리 루트에 errorElement 가 걸려 있다', () => {
    // 이것이 풀리면 렌더 오류가 다시 기본 영어 화면으로 떨어진다 — 배선 자체를 잰다.
    expect(routes).toHaveLength(1);
    expect(routes[0].errorElement).toBeDefined();
    // pathless 여야 자식의 절대 경로가 그대로 산다 — 부모에 path 가 붙으면 URL 계약이 깨진다.
    expect(Object.hasOwn(routes[0], 'path')).toBe(false);
  });
});
