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
    expect(screen.queryByText('세션 확인 중...')).not.toBeNull();
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

  it('부트스트랩 후 member면 헤더와 함께 children을 그린다', () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('member-only');
    expect(screen.queryByText('보호된 내용')).not.toBeNull();
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeNull();
    // -272 회귀 방어 — 조사까지 맞아야 한다. `{kind}` 보간으로 텍스트 노드가 쪼개져 있어
    // queryByText 로는 못 잡고 textContent 로 본다.
    expect(document.body.textContent).toContain('회원으로 이용 중');
    expect(document.body.textContent).not.toContain('회원로 이용 중');
  });

  it('게스트가 허용 화면에 들어가면 게스트 문구로 헤더를 그린다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('guest-allowed');
    expect(screen.queryByText('보호된 내용')).not.toBeNull();
    // -272 의 반대편 — '게스트'는 모음으로 끝나 '로'가 맞다. 조사를 한쪽만 고치면 여기가 깨진다.
    expect(document.body.textContent).toContain('게스트로 이용 중');
  });

  it('부트스트랩 후 guest가 member-only에 들어가면 차단하고 로그인 진입점을 준다', () => {
    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    markBootstrapped();
    renderAt('member-only');
    expect(screen.queryByText('소셜 로그인이 필요한 기능입니다.')).not.toBeNull();
    expect(screen.queryByText('보호된 내용')).toBeNull();
    // 차단은 막다른 길이 아니어야 한다(FR-011) — 로그인으로 가는 링크가 함께 있다.
    expect(screen.queryByText('로그인하러 가기')).not.toBeNull();
  });
});
