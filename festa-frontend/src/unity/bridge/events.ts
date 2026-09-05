// Unity WebGL이 보내는 전역 콜백 이벤트를 React가 구독할 수 있게 잇는 다리
// Unity → React 이벤트 계약. 패턴 출처: specs/013-avatar-customization/contracts/avatar-bridge.md
// (window.FestaUnity 콜백 네임스페이스 — 기존 Unity 합의 규격, 새 패턴을 만들지 않는다)

// 006 FR-009·FR-010: 상호작용 이벤트는 boothId+objectId 식별 포함
export type BoothInteractEvent =
  | {
      type: 'BOOTH_LAPTOP_INTERACT';
      boothId: number;
      objectId: string;
      // url 필드는 -297 에서 계약에서 제거됐다("채울 출처가 없는데 남겨 두면 Unity 가 보낼 수도
      // 있다고 읽힌다") — URL 의 정본은 GET /booths/{id} 의 homepageUrl 이다(016 C-01 #97, -374).
    }
  | {
      type: 'BOOTH_PROJECT_INTERACT'; // 계약 확정: GitLab #110 note 2754197 (2026-08-31, -343 본문)
      boothId: number; // C-01 — 부스당 프로젝트 1개라 boothId 만으로 조회가 끝난다. configId 없음
      objectId: string;
    }
  | {
      type: 'AI_AGENT_INTERACT'; // Issue #2: FE·Unity·AI 3파트 확정 (2026-08-20)
      boothId: number;
      objectId: string;
      configId: number; // Unity/Layout 계약 용어. AI_CHAT payload로는 agentId로 바뀐다 (아래 toAiChatPayload)
    }
  | {
      type: 'BOOTH_GAME_INTERACT'; // Issue #20 확정. 계약: specs/019-game-studio/contracts/game-portal-bridge.md (codex/game-studio-docs-sync, Draft v0.3)
      boothId: number;
      objectId: string;
      configId: number; // Spring 소유 Game Portal Binding 식별자(signed Int32, #34). gameId 해석은 React가 서버 조회로 한다 — AI와 달리 이름 변환 함수가 없다
    }
  | {
      type: 'BOOTH_SURVEY_INTERACT'; // 계약 확정: S15P21A604-415 (2026-09-04)
      // boothId 는 설문의 ID 가 아니라 설문을 resolve 하기 위한 **context** 다. 부스당 활성
      // 설문 1개를 boothId == surveyId 로 모델링하지 않는다 — 부스에 설문이 여럿이 되면
      // objectId 로 특정 설문에 binding 하고, 그때 Unity 계약은 바뀌지 않는다.
      boothId: number;
      objectId: string;
    };

/**
 * 부스에 속하지 않는 월드 상호작용 — 관리 데스크/NPC (S15P21A604-414).
 *
 * `BoothInteractEvent` 와 **다른 union 으로 가른다.** 그쪽은 전부 `boothId`·`objectId` 를
 * 가지며 그것이 계약의 핵심이다. 관리 진입점은 특정 부스에 종속되지 않아 필드가 없는데,
 * 같은 union 에 넣으려면 두 필드를 optional 로 낮춰야 하고 그러면 나머지 5종에서 "있을 수도
 * 있다" 가 돼 discriminated union 의 타입 안전성이 통째로 약해진다.
 *
 * 관리 대상 부스는 FE 가 `GET /booths/mine` 으로 resolve 한다 — Unity 는 세션 사용자의
 * 임대 정보를 모르고 알 필요도 없다(헌법 1조).
 */
export type WorldInteractEvent = {
  type: 'WORLD_MANAGEMENT_INTERACT';
};

/** onBoothInteract 채널로 들어오는 모든 이벤트 */
export type UnityInteractEvent = BoothInteractEvent | WorldInteractEvent;

type BoothInteractListener = (event: UnityInteractEvent) => void;

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
    let event: UnityInteractEvent;
    try {
      event = JSON.parse(json);
    } catch (err) {
      // 부스 하나의 잘못된 이벤트가 전체를 막지 않는다 (006 정신)
      console.error('[unity-bridge] onBoothInteract JSON 파싱 실패', json, err);
      return;
    }
    for (const listener of listeners) {
      try {
        listener(event);
      } catch (err) {
        // 리스너 하나의 오류가 나머지 전달과 Unity 콜백을 막지 않는다 (006 정신, -377)
        console.error('[unity-bridge] onBoothInteract listener 오류', err);
      }
    }
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
