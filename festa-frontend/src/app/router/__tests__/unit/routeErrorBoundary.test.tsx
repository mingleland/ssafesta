// @vitest-environment jsdom
// 라우트 렌더 중 throw 가 AppError 로 수용되는지 — 운영 라우트를 고의로 깨뜨리지 않고
// 같은 pathless + errorElement 구조를 테스트 전용 라우트로 세워서 잰다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { Outlet, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { AppError } from '../../ErrorScreens';

function Thrower(): never {
  throw new Error('테스트용 렌더 실패');
}

beforeEach(() => {
  // React 와 AppError 가 캐치된 오류를 콘솔에 찍는다 — 경계 동작 자체는 그대로다.
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const testRoutes = [
  {
    element: <Outlet />,
    errorElement: <AppError />,
    children: [{ path: '/boom', element: <Thrower /> }],
  },
];

describe('라우트 렌더 오류', () => {
  it('AppError 가 한글 안내와 재시도 버튼을 그린다', () => {
    render(<RouterProvider router={createMemoryRouter(testRoutes, { initialEntries: ['/boom'] })} />);
    expect(screen.getByText('페이지를 불러오지 못했습니다')).not.toBeNull();
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toBeNull();
  });

  it('오류 원문을 화면에 싣지 않고 기본 오류 화면도 보이지 않는다', () => {
    render(<RouterProvider router={createMemoryRouter(testRoutes, { initialEntries: ['/boom'] })} />);
    const text = document.body.textContent ?? '';
    expect(text).not.toContain('테스트용 렌더 실패');
    expect(text).not.toContain('Unexpected Application Error');
    expect(text).not.toContain('Hey developer');
  });
});
