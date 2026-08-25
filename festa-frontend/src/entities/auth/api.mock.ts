// 로컬 개발용 메모리 mock — 실서버 없이 OAuth 완료·게스트·refresh·logout 시맨틱을 재현한다.
// VITE_USE_MOCK=true일 때 api.ts 대신 이 모듈을 쓴다(선택은 api.select.ts에서 한다).
// 출처: specs/001-auth-user/FE/plan.md §Mock 전략, contracts/oauth-completion.md, contracts/nickname-policy.md
//
// sessionStorage는 새로고침 복원(T012 수동 검증)용 백업일 뿐이다 — 정본은 모듈 스코프 `state`
// (entities/layout/api.mock.ts와 동일 패턴). vitest(node 환경)엔 sessionStorage가 없어 매번
// ReferenceError로 catch에 빠지지만, `state`는 모듈 생존 기간 동안 메모리에 남으므로 테스트에서도
// 정상 동작한다.

import type { ApiError } from '../../shared/api/client';
import type { OAuthCompleteResponse } from './types';

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

interface HandoffRecord {
  token: string;
  providerId: string; // mock 전용 가짜 provider 계정 식별자 — 실 서버엔 없는 개념(제공자당 고정 1계정)
  consumed: boolean;
}

interface SessionMeta {
  kind: 'member' | 'guest';
  otherBrowserLogin: boolean; // "다른 브라우저 로그인" 트리거 — 이후 refresh가 실패한다(US3 AS4)
}

interface AuthMockState {
  handoff: HandoffRecord | null;
  members: Set<string>; // 가입 완료된 providerId 집합
  sessionMeta: SessionMeta | null;
}

const STORAGE_KEY = 'festa-mock-auth';

function loadState(): AuthMockState {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return { handoff: null, members: new Set(), sessionMeta: null };
    const parsed = JSON.parse(raw) as {
      handoff: HandoffRecord | null;
      members: string[];
      sessionMeta: SessionMeta | null;
    };
    return { handoff: parsed.handoff, members: new Set(parsed.members), sessionMeta: parsed.sessionMeta };
  } catch {
    return { handoff: null, members: new Set(), sessionMeta: null };
  }
}

function persistState(): void {
  try {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ handoff: state.handoff, members: Array.from(state.members), sessionMeta: state.sessionMeta }),
    );
  } catch {
    // sessionStorage 미가용(프라이빗 모드·node 테스트 환경 등) — mock은 이번 세션만 메모리로 동작
  }
}

const state = loadState();

// --- LoginPage(mock 모드)가 심는 handoff ---
// 실 흐름은 provider 왕복 후 서버가 HttpOnly cookie로 심지만(oauth-completion.md 브라우저 흐름),
// mock에는 실 provider가 없으므로 이 함수로 즉시 handoff를 만들고 /auth/callback으로 navigate한다.
// provider당 고정 계정 하나 — 같은 provider로 다시 "로그인"하면 같은 사람으로 재현된다(재로그인 시나리오).
export function mockStartOAuth(providerId: 'google' | 'kakao'): void {
  state.handoff = { token: `mock-handoff-${providerId}-${Date.now()}`, providerId, consumed: false };
  persistState();
}

// 대표 금칙 케이스 소수만(헌법 16조 판정 — 전체 금칙어 목록을 FE 번들에 전사하지 않는다).
// 완전성 검증은 서버 테스트 몫이다. 검사 규칙 1(정규화·소문자·공백/기호 제거)을 재현해
// '관리자'·'admin' 자체뿐 아니라 그 정규화 우회 변형(예: 'a-d-m-i-n', '관 리 자')도 함께 차단한다.
const RESERVED_SAMPLE = new Set(['관리자', 'admin']);
function normalizeForCheck(nickname: string): string {
  return nickname
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s\-_.]/g, '');
}
function isForbiddenNicknameSample(nickname: string): boolean {
  return RESERVED_SAMPLE.has(normalizeForCheck(nickname));
}

export async function complete(body?: { nickname: string }): Promise<OAuthCompleteResponse> {
  const handoff = state.handoff;
  if (!handoff) {
    // 400 상당 — handoff cookie 누락(oauth-completion.md FE 의무 3)
    throw apiError('OAUTH_HANDOFF_MISSING', 'OAuth 인증 정보가 없습니다. 다시 로그인해 주세요.');
  }
  if (handoff.consumed) {
    // 410 상당 — handoff 만료·재사용(oauth-completion.md FE 의무 3). client.ts의 오류 봉투에는
    // HTTP status가 없어(§0) code로만 구분한다 — 정확한 code 값은 계약 미정(§미결), 명명은 관례일 뿐이다.
    throw apiError('OAUTH_HANDOFF_EXPIRED', 'OAuth 인증이 만료되었습니다. 다시 로그인해 주세요.');
  }

  const isMember = state.members.has(handoff.providerId);

  if (!isMember) {
    if (!body?.nickname || body.nickname.trim() === '') {
      // 최초 회원(닉네임 누락) — handoff는 소비하지 않는다(FR-021c, 재제출 가능)
      return { status: 'NICKNAME_REQUIRED', accessToken: null, expiresAt: null };
    }
    if (isForbiddenNicknameSample(body.nickname)) {
      // 이유 비특정 일반 안내만(nickname-policy.md 검사 규칙 4) — handoff 보존, 재제출 가능
      throw apiError('NICKNAME_REJECTED', '사용할 수 없는 닉네임입니다. 다른 닉네임을 입력해 주세요.');
    }
    state.members.add(handoff.providerId);
  }

  handoff.consumed = true;
  state.sessionMeta = { kind: 'member', otherBrowserLogin: false };
  persistState();

  const accessToken = `mock-at-member-${handoff.providerId}-${Date.now()}`;
  const expiresAt = new Date(Date.now() + 30 * 60_000).toISOString();
  return { status: 'AUTHENTICATED', accessToken, expiresAt };
}

const GUEST_TTL_MS = 30 * 60_000; // FR-009a

export async function guestEnter(): Promise<OAuthCompleteResponse> {
  state.sessionMeta = { kind: 'guest', otherBrowserLogin: false };
  persistState();
  const accessToken = `mock-at-guest-${Date.now()}`;
  const expiresAt = new Date(Date.now() + GUEST_TTL_MS).toISOString();
  return { status: 'AUTHENTICATED', accessToken, expiresAt };
}

export async function refresh(): Promise<OAuthCompleteResponse> {
  const meta = state.sessionMeta;
  // 게스트는 재발급 대상이 아니다(FR-009a) — member가 아니거나 다른 브라우저 로그인 트리거가 서면 실패
  if (!meta || meta.kind !== 'member' || meta.otherBrowserLogin) {
    throw apiError('REFRESH_FAILED', '세션을 갱신할 수 없습니다. 다시 로그인해 주세요.');
  }
  const accessToken = `mock-at-member-refreshed-${Date.now()}`;
  const expiresAt = new Date(Date.now() + 30 * 60_000).toISOString();
  return { status: 'AUTHENTICATED', accessToken, expiresAt };
}

export async function logout(): Promise<void> {
  state.sessionMeta = null;
  persistState();
}

// quickstart 수동 검증용 — 실 API에는 없는 진입점(entities/layout/api.mock.ts __festaForceConflict와 동일 취지)
declare global {
  interface Window {
    __festaTriggerOtherBrowserLogin?: () => void;
  }
}
if (typeof window !== 'undefined') {
  window.__festaTriggerOtherBrowserLogin = () => {
    if (state.sessionMeta) state.sessionMeta = { ...state.sessionMeta, otherBrowserLogin: true };
    persistState();
  };
}

// 테스트 전용 — 모듈 스코프 상태를 테스트 간에 격리한다(unity/host/sessionManager.ts와 동일 패턴).
// 프로덕션 코드에서는 호출하지 않는다.
export function __resetAuthMockForTests(): void {
  state.handoff = null;
  state.members = new Set();
  state.sessionMeta = null;
}

// 테스트 전용 — vitest(node 환경)엔 window가 없어 위 devtools 훅을 탈 수 없다. 같은 동작을
// window 없이 직접 호출한다.
export function __triggerOtherBrowserLoginForTests(): void {
  if (state.sessionMeta) state.sessionMeta = { ...state.sessionMeta, otherBrowserLogin: true };
}
