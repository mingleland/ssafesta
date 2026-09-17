// World 화면 소유권 — "지금 월드 위에 무엇이 떠 있는가" 를 판정하는 유일한 지점.
//
// 왜 필요한가: 월드 위 레이어는 서로 다른 두 store 가 소유한다. Visitor Overlay 는 Unity 상호작용이
// 여는 자리(shared/types/overlay.ts)이고, Game Menu·Booth Management 는 사용자가 직접 여는 자리다
// (gameClientUi.ts). 그 분리 자체는 옳다 — 닫기·복귀 규칙이 다르기 때문이다(!240 R2). 문제는 판정을
// 합치는 곳이 없어서 소비자마다 일부만 보고 판단한 것이다:
//   · 입력 잠금은 Overlay Bus 만 봐서 Game Menu 가 떠도 월드가 계속 키를 먹었다 (GitLab #132)
//   · 배타 규칙이 gameMenu ↔ management 사이에만 있어서 Visitor 위에 관리 화면이 겹쳐 떴다 (GitLab #139)
//   · ESC 우선순위를 WorldPage 가 두 store 를 손으로 합성해 만들었다
//
// 그래서 store 를 합치지 않고 **위에 판정 계층만 얹는다.** 두 store 는 이 모듈을 모른다(단방향 의존).
// 레이어를 여는 길을 여기 넷으로 모으면, 새 레이어가 생겨도 배타·잠금·ESC 가 자동으로 따라온다.
import { useSyncExternalStore } from 'react';
import {
  closeOverlay,
  getCurrentOverlay,
  openOverlay,
  subscribeOverlay,
} from '../../../shared/types/overlay';
import type { OverlayType } from '../../../shared/types/overlay';
import {
  closeBoothManagement,
  closeBoothRental,
  closeGameMenu,
  closeManagementPanel,
  closeMenuPanel,
  getGameClientUiSnapshot,
  openBoothManagement,
  openBoothRental,
  openGameMenu,
  openManagementPanel,
  openMenuPanel,
  subscribeGameClientUi,
} from './gameClientUi';
import type { ManagementPanel } from './managementPanel';
import type { MenuPanel } from './gameClientUi';

/** 월드 위에 떠 있는 것. 'world' 는 아무것도 없다 = 월드가 주인이다 */
export type WorldScreen = 'world' | 'visitor' | 'management' | 'rental' | 'menu' | 'menuPanel';

// 겹칠 수 없으므로 실질은 "현재 주인" 판정이다. 그래도 순서를 명시해 두는 이유는, 배타 진입을
// 우회해 두 레이어가 동시에 켜지는 경로가 생기더라도 판정이 흔들리지 않게 하기 위해서다.
export function getWorldScreen(): WorldScreen {
  if (getCurrentOverlay() !== null) return 'visitor';
  const { managementOverlay, boothRental, gameMenu, menuPanel } = getGameClientUiSnapshot();
  // 자식이 먼저다 — 메뉴는 그 아래 배경으로 남아 있고, 자식을 닫으면 다시 드러난다
  if (menuPanel !== null) return 'menuPanel';
  if (managementOverlay) return 'management';
  if (boothRental) return 'rental';
  if (gameMenu) return 'menu';
  return 'world';
}

// 두 store 를 동시에 구독한다. 스냅샷이 문자열이라 참조 안정성은 저절로 성립한다.
function subscribeWorldScreen(onStoreChange: () => void): () => void {
  const unsubscribeOverlay = subscribeOverlay(onStoreChange);
  const unsubscribeUi = subscribeGameClientUi(onStoreChange);
  return () => {
    unsubscribeOverlay();
    unsubscribeUi();
  };
}

export function useWorldScreen(): WorldScreen {
  return useSyncExternalStore(subscribeWorldScreen, getWorldScreen);
}

// 여는 쪽이 나머지를 걷는 것이 이 계층의 계약이다 — 각 레이어가 서로를 알 필요가 없어진다.
//
// **ESC 메뉴만 예외로 배경에 남는다.** 관리·내 정보·조작 안내·설정은 메뉴에서 여는 자식이라,
// 닫았을 때 월드가 아니라 메뉴로 돌아가야 한다. Unity 가 여는 visitor 층만 메뉴를 걷는다.
function clearOthers(keep: Exclude<WorldScreen, 'world'>): void {
  if (keep !== 'visitor') closeOverlay();
  if (keep !== 'management') closeBoothManagement();
  if (keep !== 'rental') closeBoothRental();
  if (keep !== 'menuPanel') closeMenuPanel();
  if (keep === 'visitor') closeGameMenu();
}

/** Unity 상호작용이 여는 Visitor Overlay. dispatcher 와 오버레이 내부 전환이 쓴다 */
export function openVisitorOverlay(type: OverlayType, payload: unknown): void {
  clearOthers('visitor');
  openOverlay(type, payload);
}

/** Booth Management NPC 진입 */
export function openManagement(): void {
  clearOthers('management');
  // 상세가 떠 있는 채로 NPC 를 다시 부르면 관리 화면이 아니라 상세가 보인다 — 자식을 먼저 걷는다
  closeManagementPanel();
  openBoothManagement();
}

/**
 * 관리 상세 패널 진입 — 관리 화면의 **자식**이라 새 소유자를 만들지 않는다.
 *
 * 별도 슬롯을 쓰지 않는 이유는 `shared/types/overlay.ts` 가 적어 둔 그대로다: 배타·입력 잠금·
 * ESC·focus 반환이 전부 이 계층에 붙어 있어서, 슬롯을 하나 더 만들면 그 넷을 새로 배선해야 한다.
 */
export function openManagementDetail(panel: ManagementPanel): void {
  clearOthers('management');
  openManagementPanel(panel);
}

/** 부스 임대 오버레이 — 월드를 떠나지 않는다(옛 `/app/booths` 페이지 대체) */
export function openRental(): void {
  clearOthers('rental');
  openBoothRental();
}

/** ESC 개인/시스템 레이어 */
export function openMenu(): void {
  clearOthers('menu');
  openGameMenu();
}

/** ESC 메뉴의 하위 화면 — 메뉴 항목들이 연다. 닫으면 메뉴로 돌아간다 */
export function openMenuPanelScreen(panel: MenuPanel): void {
  clearOthers('menuPanel');
  openMenuPanel(panel);
}

/**
 * 현재 주인 하나만 닫는다. ESC 가 쓴다 — 무엇이 떠 있는지 호출부가 알 필요가 없다.
 *
 * 관리 화면만 자식을 갖는다. 상세가 떠 있으면 그것부터 닫고 관리 화면으로 **돌아간다** —
 * ESC 한 번이 두 겹을 함께 걷지 않는다.
 * @returns 닫은 것이 있으면 true. false 면 월드가 주인이었다는 뜻이다.
 */
export function closeTopScreen(): boolean {
  switch (getWorldScreen()) {
    case 'visitor':
      closeOverlay();
      return true;
    case 'management':
      if (getGameClientUiSnapshot().managementPanel !== null) {
        closeManagementPanel();
        return true;
      }
      closeBoothManagement();
      return true;
    case 'rental':
      closeBoothRental();
      return true;
    case 'menu':
      closeGameMenu();
      return true;
    case 'menuPanel':
      closeMenuPanel();
      return true;
    default:
      return false;
  }
}
