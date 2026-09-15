// OAuth 완료 계약의 FE측 타입 사본 — contracts/oauth-completion.md가 정본, 여기는 정확한 전사여야 한다
// (계약에 없는 필드를 추가하지 않는다 — T001 완료조건, diff 대조)
// 출처: specs/001-auth-user/contracts/oauth-completion.md

// POST /api/v1/auth/oauth/complete 200 응답 — 두 상태만 존재한다(계약 예시 그대로)
export type OAuthCompleteResponse =
  | { status: 'AUTHENTICATED'; accessToken: string; expiresAt: string }
  | { status: 'NICKNAME_REQUIRED'; accessToken: null; expiresAt: null };

// 게스트 발급·refresh 응답 — BE GuestAuthController.GuestTokenResponse 의 전사.
// **status 가 없다.** complete 만 상태 union 을 가지므로 OAuthCompleteResponse 를 여기에
// 재사용하면 status 비교가 항상 거짓이 되어 게스트 입장·401 복구·세션 복원이 조용히 죽는다.
// 성공 판정은 status 가 아니라 "예외 없음"이다 — 실패는 mock 도 real 도 throw 한다.
// 출처: origin/develop backend GuestAuthController (2026-08-26 로컬 실측 —
// {"accessToken":"eyJ...","expiresAt":"2026-08-26T09:08:03.738586500Z"}, Set-Cookie 없음)
export interface TokenResponse {
  accessToken: string;
  expiresAt: string; // BE 는 나노초 9자리로 직렬화하지만 JS Date 파싱은 정상이다(밀리초로 절삭)
}

// 세션 종류 — complete/게스트 응답에 사용자 식별 정보가 없어 발급 경로로만 구분한다
// (plan.md §상태·저장 위치)
export type SessionKind = 'anonymous' | 'guest' | 'member';
