// Game Client 의 화면 계층 상태 — World 위에 무엇이 떠 있는가 (D-08).
//
// Visitor Overlay(Overlay Bus, shared/types/overlay.ts)와 **분리한다.** 그쪽은 Unity 상호작용
// 이벤트가 여는 자리이고, 여기 둘은 사용자가 클라이언트에게 직접 요청해 여는 자리다:
//   gameMenu          ESC — 개인/시스템
//   managementOverlay Booth Management NPC — 소유자 관리
// 한 슬롯에 섞으면 "Unity 가 연 것"과 "사용자가 연 것"의 닫기·복귀 규칙이 엉킨다.
//
// 장기 Overlay Stack(game-client-experience-draft §4)은 여기서 구현하지 않는다 — 필요한 최소
// 상태 분리만 한다. Overlay Bus 와 같은 module-level store + useSyncExternalStore 관례를 따른다.
import { useSyncExternalStore } from 'react';

export interface GameClientUiState {
  gameMenu: boolean;
  managementOverlay: boolean;
}

const initialState: GameClientUiState = { gameMenu: false, managementOverlay: false };

let state: GameClientUiState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<GameClientUiState>): void {
  const next = { ...state, ...patch };
  if (next.gameMenu === state.gameMenu && next.managementOverlay === state.managementOverlay) return;
  state = next;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getGameClientUiSnapshot(): GameClientUiState {
  return state;
}

export function useGameClientUi(): GameClientUiState {
  return useSyncExternalStore(subscribe, getGameClientUiSnapshot);
}

// 둘은 서로 배타적이다 — 관리 화면 위에 게임 메뉴가 겹쳐 뜨면 ESC 의 의미가 모호해진다.
export function openGameMenu(): void {
  setState({ gameMenu: true, managementOverlay: false });
}

export function closeGameMenu(): void {
  setState({ gameMenu: false });
}

/** Booth Management NPC 진입 seam. Unity 이벤트 계약(G-1)이 오면 dispatcher 가 이 함수를 부른다 */
export function openBoothManagement(): void {
  setState({ managementOverlay: true, gameMenu: false });
}

export function closeBoothManagement(): void {
  setState({ managementOverlay: false });
}

/** World 를 벗어날 때 — 남은 레이어가 다음 진입에 그대로 떠 있지 않게 한다 */
export function resetGameClientUi(): void {
  setState(initialState);
}

// 테스트 전용
export function __resetGameClientUiForTests(): void {
  state = initialState;
  listeners.clear();
}
