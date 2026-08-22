// Unity WebGL이 보내는 전역 콜백 이벤트를 React가 구독할 수 있게 잇는 다리
// Unity → React 이벤트 계약. 패턴 출처: specs/013-avatar-customization/contracts/avatar-bridge.md
// (window.FestaUnity 콜백 네임스페이스 — 기존 Unity 합의 규격, 새 패턴을 만들지 않는다)

// 006 FR-009·FR-010: 상호작용 이벤트는 boothId+objectId 식별 포함
export type BoothInteractEvent =
  | {
      type: 'BOOTH_LAPTOP_INTERACT';
      boothId: number;
      objectId: string;
      url?: string; // LAPTOP 전용, 선택 — 016 FR-009: 주소 미등록 노트북도 상호작용은 발생하고 "안내"를 띄운다
    }
  | {
      type: 'AI_AGENT_INTERACT'; // Issue #2: FE·Unity·AI 3파트 확정 (2026-08-20)
      boothId: number;
      objectId: string;
      configId: number; // Unity/Layout 계약 용어. AI_CHAT payload로는 agentId로 바뀐다 (아래 toAiChatPayload)
    };

type BoothInteractListener = (event: BoothInteractEvent) => void;

const listeners = new Set<BoothInteractListener>();

// 입장 게이트(엘리베이터) 첫 렌더 완료 후 다음 프레임에 1회 — payload 없음, 호스트 생명주기 신호라
// BoothInteractEvent union에 넣지 않는다(부스 상호작용이 아니다). 계약: Issue #31, spec 002 FR-013·FR-014.
type WorldGateReadyListener = () => void;

const worldGateReadyListeners = new Set<WorldGateReadyListener>();

declare global {
  interface Window {
    FestaUnity?: {
      onBoothInteract?: (json: string) => void;
      onWorldGateReady?: () => void;
    };
  }
}

export function initUnityBridge(): void {
  window.FestaUnity = window.FestaUnity || {};
  window.FestaUnity.onBoothInteract = (json: string) => {
    let event: BoothInteractEvent;
    try {
      event = JSON.parse(json);
    } catch (err) {
      // 부스 하나의 잘못된 이벤트가 전체를 막지 않는다 (006 정신)
      console.error('[unity-bridge] onBoothInteract JSON 파싱 실패', json, err);
      return;
    }
    for (const listener of listeners) listener(event);
  };
  window.FestaUnity.onWorldGateReady = () => {
    for (const listener of worldGateReadyListeners) listener();
  };
}

export function subscribeBoothInteract(listener: BoothInteractListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function subscribeWorldGateReady(listener: WorldGateReadyListener): () => void {
  worldGateReadyListeners.add(listener);
  return () => {
    worldGateReadyListeners.delete(listener);
  };
}

// Unity/Layout 용어(configId)와 AI 오버레이 용어(agentId)의 경계.
// 값은 하나이고 이름만 다르다 — Issue #2에서 3파트 합의(역할분담 §8.3의 "착수 전 공동 확정").
// Interaction Dispatcher가 Unity 이벤트를 Overlay Platform 호출로 바꾸는 지점 (역할분담 §2.3·§5.2).
export function toAiChatPayload(
  event: Extract<BoothInteractEvent, { type: 'AI_AGENT_INTERACT' }>,
): { boothId: number; agentId: number } {
  return { boothId: event.boothId, agentId: event.configId };
}
