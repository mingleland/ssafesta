// Unity → React 이벤트 계약. 패턴 출처: specs/013-avatar-customization/contracts/avatar-bridge.md
// (window.FestaUnity 콜백 네임스페이스 — 기존 Unity 합의 규격, 새 패턴을 만들지 않는다)

// 006 FR-009·FR-010: 상호작용 이벤트는 boothId+objectId 식별 포함
export interface BoothInteractEvent {
  type: 'BOOTH_LAPTOP_INTERACT'; // 후속 spec에서 union 확장 (AI_AGENT_INTERACT 등)
  boothId: number;
  objectId: string;
  url?: string; // LAPTOP 전용, 선택 — 016 FR-009: 주소 미등록 노트북도 상호작용은 발생하고 "안내"를 띄운다
}

type BoothInteractListener = (event: BoothInteractEvent) => void;

const listeners = new Set<BoothInteractListener>();

declare global {
  interface Window {
    FestaUnity?: {
      onBoothInteract?: (json: string) => void;
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
}

export function subscribeBoothInteract(listener: BoothInteractListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
