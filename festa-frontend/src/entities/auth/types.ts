// OAuth 완료 계약의 FE측 타입 사본 — contracts/oauth-completion.md가 정본, 여기는 정확한 전사여야 한다
// (계약에 없는 필드를 추가하지 않는다 — T001 완료조건, diff 대조)
// 출처: specs/001-auth-user/contracts/oauth-completion.md

// POST /api/v1/auth/oauth/complete 200 응답 — 두 상태만 존재한다(계약 예시 그대로)
export type OAuthCompleteResponse =
  | { status: 'AUTHENTICATED'; accessToken: string; expiresAt: string }
  | { status: 'NICKNAME_REQUIRED'; accessToken: null; expiresAt: null };

// 세션 종류 — complete/게스트 응답에 사용자 식별 정보가 없어 발급 경로로만 구분한다
// (plan.md §상태·저장 위치)
export type SessionKind = 'anonymous' | 'guest' | 'member';
