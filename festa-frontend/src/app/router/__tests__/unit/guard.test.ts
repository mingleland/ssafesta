// 라우트 가드 판정 함수 회귀 방어(T014a) — kind 3종 × level 2등급 표 그대로.
import { describe, expect, it } from 'vitest';
import { evaluateGuard } from '../../guard';

describe('evaluateGuard', () => {
  it('anonymous는 어떤 등급이든 로그인으로 redirect한다', () => {
    expect(evaluateGuard('anonymous', 'guest-allowed')).toBe('redirect-login');
    expect(evaluateGuard('anonymous', 'member-only')).toBe('redirect-login');
  });

  it('guest는 guest-allowed면 허용, member-only면 차단(로그인 안내)한다', () => {
    expect(evaluateGuard('guest', 'guest-allowed')).toBe('allow');
    expect(evaluateGuard('guest', 'member-only')).toBe('block-member-only');
  });

  it('member는 어떤 등급이든 허용한다', () => {
    expect(evaluateGuard('member', 'guest-allowed')).toBe('allow');
    expect(evaluateGuard('member', 'member-only')).toBe('allow');
  });
});
