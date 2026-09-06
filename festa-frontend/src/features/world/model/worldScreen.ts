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
  closeGameMenu,
  getGameClientUiSnapshot,
  openBoothManagement,
  openGameMenu,
  subscribeGameClientUi,
} from './gameClientUi';

/** 월드 위에 떠 있는 것. 'world' 는 아무것도 없다 = 월드가 주인이다 */
export type WorldScreen = 'world' | 'visitor' | 'management' | 'menu';

// 겹칠 수 없으므로 실질은 "현재 주인" 판정이다. 그래도 순서를 명시해 두는 이유는, 배타 진입을
// 우회해 두 레이어가 동시에 켜지는 경로가 생기더라도 판정이 흔들리지 않게 하기 위해서다.
export function getWorldScreen(): WorldScreen {
  if (getCurrentOverlay() !== null) return 'visitor';
  const { managementOverlay, gameMenu } = getGameClientUiSnapshot();
  if (managementOverlay) return 'management';
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

// 월드 위에는 한 번에 하나만 뜬다. 여는 쪽이 나머지를 걷는 것이 이 계층의 계약이다 —
// 각 레이어가 서로를 알 필요가 없어진다.
function clearOthers(keep: Exclude<WorldScreen, 'world'>): void {
  if (keep !== 'visitor') closeOverlay();
  if (keep !== 'management') closeBoothManagement();
  if (keep !== 'menu') closeGameMenu();
}

/** Unity 상호작용이 여는 Visitor Overlay. dispatcher 와 오버레이 내부 전환이 쓴다 */
export function openVisitorOverlay(type: OverlayType, payload: unknown): void {
  clearOthers('visitor');
  openOverlay(type, payload);
}

/** Booth Management NPC 진입 */
export function openManagement(): void {
  clearOthers('management');
  openBoothManagement();
}

/** ESC 개인/시스템 레이어 */
export function openMenu(): void {
  clearOthers('menu');
  openGameMenu();
}

/**
 * 현재 주인 하나만 닫는다. ESC 가 쓴다 — 무엇이 떠 있는지 호출부가 알 필요가 없다.
 * @returns 닫은 것이 있으면 true. false 면 월드가 주인이었다는 뜻이다.
 */
export function closeTopScreen(): boolean {
  switch (getWorldScreen()) {
    case 'visitor':
      closeOverlay();
      return true;
    case 'management':
      closeBoothManagement();
      return true;
    case 'menu':
      closeGameMenu();
      return true;
    default:
      return false;
  }
}
