// 라우트 가드 판정 함수만 순수 로직으로 분리 — vitest include가 src/**/*.test.ts만 잡아
// 컴포넌트(.tsx)는 자동 테스트 대상이 아니다(T014a, plan.md §라우트 설계).
// 가드는 UX 보조이며 서버 401/403 인가를 대체하지 않는다(docs/10 §3, 헌법 16조 판정).
import type { SessionKind } from '../../entities/auth/types';

export type GuardLevel = 'guest-allowed' | 'member-only';
export type GuardDecision = 'allow' | 'redirect-login' | 'block-member-only';

// kind 3종 × level 2등급 조합표(plan.md §라우트 설계):
// anonymous  → 항상 redirect-login (게스트 허용 라우트도 최소 게스트 토큰은 필요)
// guest      → guest-allowed면 allow, member-only면 block-member-only(FR-011 로그인 진입점 안내)
// member     → 항상 allow
export function evaluateGuard(kind: SessionKind, level: GuardLevel): GuardDecision {
  if (kind === 'anonymous') return 'redirect-login';
  if (level === 'guest-allowed') return 'allow';
  return kind === 'member' ? 'allow' : 'block-member-only';
}
