// 세션 종류·만료 시각을 관리하는 단일 store — setAccessToken(client.ts)과 항상 함께 갱신해
// 토큰과 kind가 어긋나는 경로를 없앤다(T005 완료조건). useSyncExternalStore로 React 구독을 지원한다.
// 출처: specs/001-auth-user/FE/plan.md §상태·저장 위치

import { useSyncExternalStore } from 'react';
import { setAccessToken } from '../../../shared/api/client';
import type { SessionKind } from '../../../entities/auth/types';

// 401 인터셉트(features/auth/model/unauthorizedHandler.ts)가 세션을 강제 종료할 때 남기는 사유 —
// LoginPage가 이 값을 읽어 안내 문구를 고른다.
export type SessionNotice = 'session-expired' | 'guest-reentry-required' | null;

export interface SessionState {
  kind: SessionKind;
  expiresAt: string | null;
  notice: SessionNotice;
  // 부트스트랩(새로고침 복원 refresh)이 끝나기 전에는 가드가 redirect를 확정하면 안 된다 —
  // 초기 anonymous는 "미확인"이지 "비로그인 확정"이 아니다(T012 레이스, quickstart §6 실측으로 발견).
  bootstrapped: boolean;
  /**
   * Access Token 이 바뀔 때마다 1 증가한다 (S15P21A604-828).
   *
   * 토큰 값 자체는 이 store 에 두지 않는다(헌법 13조 — 넘길 곳이 늘면 새는 곳도 늘어난다).
   * 그런데 구독자가 "토큰이 바뀌었다" 를 알 방법이 `expiresAt` 변화뿐이라, 만료 시각이 같은
   * 갱신은 아무에게도 전달되지 않았다. Unity 는 그 신호 하나로 재주입을 결정하므로 놓치면
   * 죽은 토큰을 계속 들고 있게 된다. 세는 값 하나면 값을 내보내지 않고도 변화를 알릴 수 있다.
   */
  tokenVersion: number;
}

let state: SessionState = { kind: 'anonymous', expiresAt: null, notice: null, bootstrapped: false, tokenVersion: 0 };
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** React 밖에서 세션 변화를 구독한다 — 만료 전 예약 갱신(refreshScheduler)이 쓴다 */
export function subscribeSession(listener: () => void): () => void {
  return subscribe(listener);
}

function getSnapshot(): SessionState {
  return state;
}

// React 트리 밖(unauthorizedHandler 등)에서 현재 세션을 읽을 때 쓴다
export function getSessionSnapshot(): SessionState {
  return state;
}

// 아래 3개 함수가 setAccessToken(client.ts)과 kind를 갱신하는 유일한 진입점이다 — 다른 곳에서
// setAccessToken을 직접 부르지 않는다(T005 완료조건: 토큰과 kind가 어긋나는 경로 없음).
export function setMemberSession(accessToken: string, expiresAt: string): void {
  setAccessToken(accessToken);
  state = { ...state, kind: 'member', expiresAt, notice: null, tokenVersion: state.tokenVersion + 1 };
  emit();
}

export function setGuestSession(accessToken: string, expiresAt: string): void {
  setAccessToken(accessToken);
  state = { ...state, kind: 'guest', expiresAt, notice: null, tokenVersion: state.tokenVersion + 1 };
  emit();
}

export function clearSession(notice: SessionNotice = null): void {
  setAccessToken(null);
  state = { ...state, kind: 'anonymous', expiresAt: null, notice, tokenVersion: state.tokenVersion + 1 };
  emit();
}

// bootstrapAuth가 refresh 성패와 무관하게 종료 시점에 1회 호출 — 이때부터 가드 판정이 유효하다.
export function markBootstrapped(): void {
  if (state.bootstrapped) return;
  state = { ...state, bootstrapped: true };
  emit();
}

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, getSnapshot);
}

// 테스트 전용 — 모듈 스코프 상태를 테스트 간에 격리한다(unity/host/sessionManager.ts와 동일 패턴).
// 프로덕션 코드에서는 호출하지 않는다.
export function __resetSessionForTests(): void {
  setAccessToken(null);
  state = { kind: 'anonymous', expiresAt: null, notice: null, bootstrapped: false, tokenVersion: 0 };
  listeners.clear();
}
