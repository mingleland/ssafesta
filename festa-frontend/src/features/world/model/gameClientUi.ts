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
import type { ManagementPanel } from './managementPanel';

/** ESC 메뉴가 여는 하위 화면 */
export type MenuPanel = 'myInfo' | 'guide' | 'settings';

/**
 * World 하단 개발용 상호작용 트리거(DEV_ONLY)를 켤지 — dev 빌드 + 명시적 플래그를 동시에 요구한다.
 * 제품 HUD 가 아니라서(hud-decisions: 기능 Launcher 금지) 프로덕션에서는 상수 false 가 되어
 * 번들에서 사라진다.
 */
export const IS_DEV_INTERACTION_BAR =
  import.meta.env.DEV && import.meta.env.VITE_DEV_INTERACTION_BAR === 'true';

export interface GameClientUiState {
  gameMenu: boolean;
  managementOverlay: boolean;
  /**
   * 관리 오버레이 **안에서** 열린 상세 패널. 별도 소유자가 아니라 자식이다 —
   * `worldScreen` 은 이때도 `'management'` 를 말하고, ESC 만 상세 → 관리 → 월드 순으로 한 겹씩
   * 벗긴다. 이렇게 두어야 상세를 닫았을 때 관리 화면으로 **돌아온다**.
   */
  managementPanel: ManagementPanel | null;
  /**
   * ESC 메뉴 **안에서** 열린 화면. 관리 상세와 같은 부모-자식 관계다 — 이것이 떠 있는 동안
   * `gameMenu` 는 켜진 채로 남고, 닫으면 메뉴가 다시 드러난다.
   */
  menuPanel: MenuPanel | null;
}

const initialState: GameClientUiState = {
  gameMenu: false,
  managementOverlay: false,
  managementPanel: null,
  menuPanel: null,
};

let state: GameClientUiState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<GameClientUiState>): void {
  const next = { ...state, ...patch };
  if (
    next.gameMenu === state.gameMenu &&
    next.managementOverlay === state.managementOverlay &&
    next.managementPanel === state.managementPanel &&
    next.menuPanel === state.menuPanel
  ) {
    return;
  }
  state = next;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** 훅 밖에서 이 store 의 변화를 듣는 길 — worldScreen 이 두 store 를 합쳐 구독할 때 쓴다 */
export function subscribeGameClientUi(listener: () => void): () => void {
  return subscribe(listener);
}

export function getGameClientUiSnapshot(): GameClientUiState {
  return state;
}

export function useGameClientUi(): GameClientUiState {
  return useSyncExternalStore(subscribe, getGameClientUiSnapshot);
}

export function openGameMenu(): void {
  setState({ gameMenu: true, managementOverlay: false, managementPanel: null, menuPanel: null });
}

export function closeGameMenu(): void {
  setState({ gameMenu: false });
}

/** Booth Management NPC 진입 seam. Unity 이벤트 계약(G-1)이 오면 dispatcher 가 이 함수를 부른다 */
export function openBoothManagement(): void {
  // gameMenu 를 끄지 않는다 — 메뉴에서 열었으면 닫을 때 메뉴로 돌아가야 한다. NPC 로 열 때는
  // 애초에 메뉴가 꺼져 있어 영향이 없다.
  setState({ managementOverlay: true, menuPanel: null });
}

export function closeBoothManagement(): void {
  // 관리 화면이 닫히면 그 자식도 같이 사라진다 — 부모 없는 상세가 월드 위에 남으면 ESC 가
  // 닫을 대상을 잃는다.
  setState({ managementOverlay: false, managementPanel: null });
}

/**
 * 관리 상세를 연다. 관리 오버레이가 닫혀 있으면 함께 연다 — 상세는 그 위에 얹히는 자식이고,
 * 닫으면 관리 화면으로 돌아가야 하기 때문이다.
 */
export function openManagementPanel(panel: ManagementPanel): void {
  setState({ managementOverlay: true, managementPanel: panel, menuPanel: null });
}

export function closeManagementPanel(): void {
  setState({ managementPanel: null });
}

/**
 * Booth Studio·관리 상세처럼 World 를 떠나는 화면에서 돌아올 때 쓰는 경로.
 * 그 화면들은 `/app/world?panel=management` 로 돌아오고 WorldPage 가 이 값을 읽어 관리 화면을
 * 다시 연다 — 모듈 상태에 "복귀 예약"을 남기지 않는 이유는 StrictMode 의 mount→cleanup→mount
 * 사이에 그 예약이 소비된 뒤 cleanup 이 화면을 다시 닫아 버리기 때문이다(실측). URL 로 표현하면
 * 새로고침·뒤로가기에도 같은 결과가 된다.
 */
export const WORLD_RETURN_TO_MANAGEMENT = '/app/world?panel=management';

/** ESC 메뉴의 하위 화면을 연다 — 메뉴는 켜진 채로 둔다(닫으면 돌아간다) */
export function openMenuPanel(panel: MenuPanel): void {
  setState({ menuPanel: panel, managementOverlay: false, managementPanel: null });
}

export function closeMenuPanel(): void {
  setState({ menuPanel: null });
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
