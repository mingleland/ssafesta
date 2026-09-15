// @vitest-environment jsdom
// G-4 컴포넌트 테스트 환경의 첫 대상 — RequireAuth의 `bootstrapped` 분기(T-17이 난 바로 그 틈).
// evaluateGuard는 guard.test.ts가 이미 덮는다. 여기서 보는 것은 순수 함수로 뺄 수 없는 부분,
// 즉 "부트스트랩 전 anonymous를 비로그인으로 확정하지 않는다"는 렌더 시점 판단이다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RequireAuth } from '../../RequireAuth';
import {
  __resetSessionForTests,
  markBootstrapped,
  setMemberSession,
  setGuestSession,
} from '../../../../features/auth/model/session';
import type { GuardLevel } from '../../guard';

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

// /protected 에 가드를 걸고, redirect 목적지인 /login 은 표식만 그린다 —
// Navigate가 실제로 일어났는지를 목적지 텍스트로 판정한다.
function renderAt(level: GuardLevel) {
  return render(
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route
          path="/protected"
          element={
            <RequireAuth level={level}>
              <p>보호된 내용</p>
            </RequireAuth>
          }
        />
        <Route path="/login" element={<p>로그인 화면</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireAuth — bootstrapped 분기', () => {
  it('부트스트랩 전에는 redirect하지 않고 확인 중 상태를 보여준다', () => {
    renderAt('guest-allowed');
    expect(screen.queryByText('입장 정보를 확인하고 있어요...')).not.toBeNull();
    // 이 줄이 T-17의 회귀 방어다 — 부트스트랩 전 anonymous를 비로그인으로 확정하면
    // 새로고침 복원(refresh)이 이길 수 없는 레이스가 된다.
    expect(screen.queryByText('로그인 화면')).toBeNull();
    expect(screen.queryByText('보호된 내용')).toBeNull();
  });

  it('부트스트랩 후 anonymous면 로그인으로 redirect한다', () => {
    markBootstrapped();
    renderAt('guest-allowed');
    expect(screen.queryByText('로그인 화면')).not.toBeNull();
    expect(screen.queryByText('보호된 내용')).toBeNull();
  });

  it('부트스트랩 후 member면 children만 그린다 — 계정 UI 를 덧그리지 않는다', () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('member-only');
    expect(screen.queryByText('보호된 내용')).not.toBeNull();
    // D-08 — 가드는 인증 판정만 한다. 계정 표시·로그아웃은 ESC Game Menu 소관이라
    // 여기서 그리면 World 같은 상주 화면 위에 계정 칩이 박힌다.
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull();
    expect(document.body.textContent).not.toContain('이용 중');
  });

  it('게스트가 허용 화면에 들어가도 children만 그린다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('guest-allowed');
    expect(screen.queryByText('보호된 내용')).not.toBeNull();
    expect(document.body.textContent).not.toContain('이용 중');
  });

  it('부트스트랩 후 guest가 member-only에 들어가면 차단하고 로그인 진입점을 준다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('member-only');
    expect(screen.queryByText('소셜 로그인 회원만 이용할 수 있는 기능입니다.')).not.toBeNull();
    expect(screen.queryByText('보호된 내용')).toBeNull();
    // 차단은 막다른 길이 아니어야 한다(FR-011) — 로그인으로 가는 링크가 함께 있다.
    expect(screen.queryByText('로그인하러 가기')).not.toBeNull();
  });
});
