// Unity WebGL이 보내는 전역 콜백 이벤트를 React가 구독할 수 있게 잇는 다리
// Unity → React 이벤트 계약. 패턴 출처: specs/013-avatar-customization/contracts/avatar-bridge.md
// (window.FestaUnity 콜백 네임스페이스 — 기존 Unity 합의 규격, 새 패턴을 만들지 않는다)
import { applyWorldUiStateJson } from './worldUiState';

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
export type WorldInteractEvent =
  | {
      type: 'WORLD_MANAGEMENT_INTERACT';
    }
  | {
      /**
       * 이벤트 NPC 상호작용 — 경품 상점을 연다 (S15P21A604-599).
       *
       * 부스에 속하지 않아 `boothId`·`objectId` 가 없다. `WORLD_MANAGEMENT_INTERACT` 와 같은 규칙이고
       * 같은 `onBoothInteract` 채널로 온다 — 새 callback 을 만들지 않는다.
       *
       * `npcId` 는 씬 canonical id 로 로그·분석용이다. FE 는 해석하지 않는다 — 지금은 상점이 하나뿐이라
       * 분기에 쓰이지 않고, 여러 이벤트 NPC 가 생기면 그때 이 값이 의미를 얻는다.
       *
       * ⚠️ Unity 송신부는 아직 없다. 이 타입은 FE 수신부가 먼저 서 있다는 뜻이고, 게임 파트가 보내기
       * 시작하면 이 자리가 그대로 실경로가 된다(`BOOTH_PROJECT_INTERACT` 가 -343 에서 그랬던 것처럼).
       */
      type: 'WORLD_EVENT_INTERACT';
      npcId?: string;
    }
  | {
      /**
       * 내장 미니게임 상호작용 — 타이밍 스톱 (S15P21A604-601, GitLab #166).
       *
       * 부스에 속하지 않아 `boothId`·`objectId` 가 없다. 같은 `onBoothInteract` 채널로 온다.
       *
       * `WORLD_ARCADE_INTERACT`(#135, Game Studio 게시 게임)와 **합치지 않는다.** 그쪽은
       * `machineId → 서버 resolve` 로 "어느 게임기인가"(물리 오브젝트 분류)가 식별 축이고, 이쪽은
       * `gameId` 로 "어떤 기능인가"가 축이다. 합치면 소비처가 `gameId` 유무로 다시 갈라야 해서
       * dispatcher 의 `switch (event.type)` 하나로 끝나는 성질을 잃는다.
       *
       * `gameId` 는 내장 미니게임 식별자(지금은 `TIMER_STOP` 하나)이고 화면 선택에 쓴다.
       * `machineId` 는 씬 canonical id 로 로그·분석용이다 — FE 는 해석하지 않는다.
       *
       * ⚠️ Unity 송신부는 아직 develop 에 없다. `BOOTH_PROJECT_INTERACT`(-343)·
       * `WORLD_EVENT_INTERACT`(-599)와 같이 **FE 수신부가 먼저 서 있는** 상태이고, 게임 파트가
       * 보내기 시작하면 이 자리가 그대로 실경로가 된다.
       */
      type: 'WORLD_MINIGAME_INTERACT';
      gameId: string;
      machineId?: string;
    }
  | {
      /**
       * 부스 안/밖 컨텍스트 (S15P21A604-627, GitLab #174).
       *
       * **상호작용이 아니라 위치 알림이다.** 같은 `onBoothInteract` 채널로 오지만 화면을 열지
       * 않는다 — dispatcher 가 `worldContext` 에 담아 두면, 부스 안일 때만 나가기 버튼이 뜬다.
       *
       * `WORLD_` 접두사인 이유는 `WORLD_MINIGAME_INTERACT`(#166)·`WORLD_EVENT_INTERACT`(-599)와
       * 같다 — 화면이 `boothId` 를 **소비하지 않는다**. 버튼은 `insideBooth` 만 보고, `boothId` 는
       * 로그·분석용이다.
       *
       * **밖일 때는 `boothId` 키 자체가 오지 않는다.** Unity 송신부가 그렇게 만든다
       * (`BoothInteractBridge.SendBoothContext`) — `0` 을 실어 보내면 언젠가 "0번 부스" 로 읽히기
       * 때문이다. 그래서 optional 이고, 판정은 `insideBooth` 하나로만 한다.
       *
       * 보내는 시점: 로컬 플레이어 생성 직후 1회 + 안↔밖이 바뀔 때만. 진입 직후 1회가 필요한
       * 이유는 재접속·재시도 boot 에서 FE 가 초기값을 모르기 때문이다(`AudioBridge` 의 mute
       * 동기화 -557 과 같은 이유).
       */
      type: 'WORLD_BOOTH_CONTEXT';
      insideBooth: boolean;
      boothId?: number;
    }
  | {
      /**
       * 안내데스크 NPC — 이용 안내 화면 (S15P21A604-688, GitLab #184).
       *
       * **payload 가 없다.** 화면이 `boothId` 도 `npcId` 도 소비하지 않는다 — 안내 데스크가
       * 하나뿐이고, 열리는 것은 부스에 속하지 않는 `WORLD_GUIDE` 오버레이다. 여러 곳이 생기면
       * 그때 식별자를 싣는다(게임 파트도 같은 판단, #184).
       *
       * `WORLD_` 접두사인 이유는 `WORLD_EVENT_INTERACT`(-599)·`WORLD_MINIGAME_INTERACT`(#166)와
       * 같다 — 부스 컨텍스트를 쓰지 않는 월드 상호작용이다.
       *
       * 말풍선("F 를 눌러 이용 안내를 보세요")은 Unity 가 월드 안에 그린다. FE 계약을 늘리지
       * 않으려는 것이고 부스 간판·이름표와 같은 방식이다.
       *
       * ⚠️ Unity 송신부는 아직 develop 에 없다. `WORLD_EVENT_INTERACT` 와 같이 **FE 수신부가
       * 먼저 서 있는** 상태이고, 게임 파트가 보내기 시작하면 이 자리가 그대로 실경로가 된다.
       */
      type: 'WORLD_GUIDE_INTERACT';
    };

/** onBoothInteract 채널로 들어오는 모든 이벤트 */
export type UnityInteractEvent = BoothInteractEvent | WorldInteractEvent;

type BoothInteractListener = (event: UnityInteractEvent) => void;

const listeners = new Set<BoothInteractListener>();

// 입장 게이트(엘리베이터) 첫 렌더 완료 후 다음 프레임에 1회 — payload 없음, 호스트 생명주기 신호라
// BoothInteractEvent union에 넣지 않는다(부스 상호작용이 아니다). 계약: Issue #31, spec 002 FR-013·FR-014.
type WorldGateReadyListener = () => void;

const worldGateReadyListeners = new Set<WorldGateReadyListener>();

// 월드 로드 시작 — 로비에서 사용자가 월드 입장을 눌러 main 씬 로드가 시작되는 순간 1회 (S15P21A604-429).
// onWorldGateReady 하나만으로는 "로비에 머무는 중"과 "월드를 불러오는 중"이 구분되지 않는다. 앞은 사용자
// 시간이라 안내가 없어야 하고 뒤는 50~84초 대기라 안내가 있어야 한다(#128). Unity 가 아직 이 신호를 보내지
// 않으면 호스트는 지금과 똑같이 동작한다 — 신호가 도착하면 그때부터 안내가 켜진다.
type WorldLoadStartListener = () => void;

const worldLoadStartListeners = new Set<WorldLoadStartListener>();

// 월드 접속 상태 (S15P21A604-432, #131). Unity 가 끊김 감지·재접속·포기까지 스스로 하고 그 상태만 밀어 준다 —
// FE 는 표시와 복구 동선만 맡고 재시도 타이머·소켓 재연결을 다시 구현하지 않는다(중복 lifecycle 금지).
//
// detail 의 의미가 state 마다 다르다(Unity WorldReconnector.cs 실물 기준):
//   'reconnecting' → 시도 회차 문자열("1".."5"). 회차 상한은 Unity 가 정하므로 FE 가 개수를 가정하지 않는다
//   그 밖         → 서버 사유 문자열. 빈 문자열은 무응답이고, 목록에 없는 값이 올 수 있다(fallback 필수)
export type WorldConnectionState = 'connected' | 'disconnected' | 'reconnecting' | 'failed';

type WorldConnectionStateListener = (state: WorldConnectionState, detail: string) => void;

const worldConnectionStateListeners = new Set<WorldConnectionStateListener>();

declare global {
  interface Window {
    FestaUnity?: {
      onBoothInteract?: (json: string) => void;
      onWorldGateReady?: () => void;
      onWorldLoadStart?: () => void;
      onWorldConnectionState?: (state: string, detail: string) => void;
      onWorldUiState?: (json: string) => void;
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
  window.FestaUnity.onWorldLoadStart = () => {
    for (const listener of worldLoadStartListeners) listener();
  };
  window.FestaUnity.onWorldConnectionState = (state: string, detail: string) => {
    // Unity 가 보낸 문자열을 그대로 신뢰하지 않는다 — 계약 밖 값이 오면 무시하고 로그로 드러낸다(T-24 정신)
    if (!isWorldConnectionState(state)) {
      console.error('[unity-bridge] 알 수 없는 onWorldConnectionState state', state, detail);
      return;
    }
    for (const listener of worldConnectionStateListeners) {
      try {
        listener(state, detail ?? '');
      } catch (err) {
        console.error('[unity-bridge] onWorldConnectionState listener 오류', err);
      }
    }
  };
  // Unity 모달 상태 (S15P21A604-450, #132). 판정·저장은 worldUiState 가 한다 — 이 파일은
  // window.FestaUnity 배선만 갖는다. Unity 가 아직 안 보내면 예전과 똑같이 동작한다.
  window.FestaUnity.onWorldUiState = (json: string) => {
    applyWorldUiStateJson(json);
  };
}

const WORLD_CONNECTION_STATES: readonly string[] = ['connected', 'disconnected', 'reconnecting', 'failed'];

function isWorldConnectionState(value: string): value is WorldConnectionState {
  return WORLD_CONNECTION_STATES.includes(value);
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

export function subscribeWorldLoadStart(listener: WorldLoadStartListener): () => void {
  worldLoadStartListeners.add(listener);
  return () => {
    worldLoadStartListeners.delete(listener);
  };
}

export function subscribeWorldConnectionState(listener: WorldConnectionStateListener): () => void {
  worldConnectionStateListeners.add(listener);
  return () => {
    worldConnectionStateListeners.delete(listener);
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
