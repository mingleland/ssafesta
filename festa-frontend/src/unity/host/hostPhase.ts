// Unity 호스트가 지금 어느 단계인가 — 화면이 그 단계를 읽을 수 있게 밖으로 노출한다
// (S15P21A604-613).
//
// 왜 필요한가: `UnityHost` 는 이 값을 로컬 state 로만 들고 있어서, 형제로 그려지는 World HUD 가
// "지금 캐릭터 선택 중인지, 월드 안인지" 를 알 수 없었다. 그래서 Unity 캐릭터 선택 화면 위에
// 조작 안내·상담·도움말이 그대로 겹쳐 떴다.
//
// **새 Unity 계약이 아니다.** 이미 받고 있는 생명주기 신호(`onWorldGateReady`·`onWorldLoadStart`)로
// UnityHost 가 판정한 결과를 그대로 옮겨 담는다 — 판정 주체는 여전히 UnityHost 한 곳이다.
//
// Overlay Bus(shared/types/overlay)·gameClientUi·worldUiState 와 같은 module-level store 관례를 따른다.
import { useSyncExternalStore } from 'react';

/**
 * - `booting`         인스턴스 부팅 중
 * - `waiting-gate`    인스턴스는 섰고 Unity 가 로비·게이트를 그린다 (**캐릭터 선택이 여기**)
 * - `preparing-world` 로비에서 입장을 눌러 main 씬 로드 중
 * - `ready`           `onWorldGateReady` 이후 — 월드 안이다
 * - `failed`          boot 실패
 */
export type HostPhase = 'booting' | 'waiting-gate' | 'preparing-world' | 'ready' | 'failed';

let phase: HostPhase = 'booting';
const listeners = new Set<() => void>();

export function setHostPhase(next: HostPhase): void {
  if (phase === next) return;
  phase = next;
  for (const listener of listeners) listener();
}

export function getHostPhase(): HostPhase {
  return phase;
}

export function subscribeHostPhase(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useHostPhase(): HostPhase {
  return useSyncExternalStore(subscribeHostPhase, getHostPhase, getHostPhase);
}

/** 테스트 전용 — 모듈 상태를 테스트 간에 격리한다 */
export function __resetHostPhaseForTests(): void {
  phase = 'booting';
  listeners.clear();
}
