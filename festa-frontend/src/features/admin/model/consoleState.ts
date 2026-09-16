// 콘솔이 어느 섹션을 보고 무엇을 고른 상태인가 (S15P21A604-828).
//
// URL 검색 파라미터에 두지 않는다. 콘솔은 월드 위 오버레이라 주소는 계속 `/app/world` 여야 하고,
// 여기에 `?userId=` 를 얹으면 월드의 주소가 콘솔 내부 상태를 들고 다니게 된다 — 오버레이를 닫아도
// 남고, 새로고침이 월드가 아니라 콘솔 상태를 복원한다.
//
// 대신 월드 상주 오버레이 관례를 따른다(gameClientUi·worldUiState 와 같은 module-level store +
// useSyncExternalStore). 오버레이를 닫으면 `resetAdminConsole` 로 비워, 다시 열 때 첫 화면에서 시작한다.
import { useSyncExternalStore } from 'react';
import type { AdminSectionId } from './sections';

export interface AdminConsoleState {
  section: AdminSectionId;
  /** 회원·지갑이 공유하는 대상. 한쪽에서 고른 사람이 다른 쪽에서도 그대로 열린다 */
  userId: number | null;
  surveyKey: string | null;
  responseId: number | null;
}

const initialState: AdminConsoleState = { section: 'overview', userId: null, surveyKey: null, responseId: null };

let state: AdminConsoleState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): AdminConsoleState {
  return state;
}

function setState(patch: Partial<AdminConsoleState>): void {
  const next = { ...state, ...patch };
  if (
    next.section === state.section &&
    next.userId === state.userId &&
    next.surveyKey === state.surveyKey &&
    next.responseId === state.responseId
  ) {
    return;
  }
  state = next;
  emit();
}

export function useAdminConsole(): AdminConsoleState {
  return useSyncExternalStore(subscribe, getSnapshot);
}

export function getAdminConsoleSnapshot(): AdminConsoleState {
  return state;
}

/**
 * 섹션 이동. 설문 쪽 선택은 함께 비운다 — 다른 섹션을 다녀온 뒤 돌아왔을 때 옛 응답 상세가
 * 열린 채로 있으면 그것이 방금 고른 것으로 읽힌다. `userId` 는 남긴다: 회원 → 지갑 이동이
 * 같은 사람을 이어서 보는 흐름이라 그게 이 콘솔의 주된 동선이다.
 */
export function openAdminSection(section: AdminSectionId): void {
  setState({ section, responseId: null });
}

export function selectAdminUser(userId: number | null): void {
  setState({ userId });
}

export function selectEventSurvey(surveyKey: string | null): void {
  setState({ surveyKey, responseId: null });
}

export function selectEventResponse(responseId: number | null): void {
  setState({ responseId });
}

/** 오버레이를 닫을 때 — 다음에 열면 운영 현황부터 시작한다 */
export function resetAdminConsole(): void {
  setState(initialState);
}

export function __resetAdminConsoleForTests(): void {
  state = initialState;
  listeners.clear();
}

