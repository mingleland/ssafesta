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
}

let state: SessionState = { kind: 'anonymous', expiresAt: null, notice: null };
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
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
  state = { kind: 'member', expiresAt, notice: null };
  emit();
}

export function setGuestSession(accessToken: string, expiresAt: string): void {
  setAccessToken(accessToken);
  state = { kind: 'guest', expiresAt, notice: null };
  emit();
}

export function clearSession(notice: SessionNotice = null): void {
  setAccessToken(null);
  state = { kind: 'anonymous', expiresAt: null, notice };
  emit();
}

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, getSnapshot);
}

// 테스트 전용 — 모듈 스코프 상태를 테스트 간에 격리한다(unity/host/sessionManager.ts와 동일 패턴).
// 프로덕션 코드에서는 호출하지 않는다.
export function __resetSessionForTests(): void {
  setAccessToken(null);
  state = { kind: 'anonymous', expiresAt: null, notice: null };
  listeners.clear();
}
