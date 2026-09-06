// Interaction Dispatcher — Unity 이벤트를 FE 의미(Overlay 요청)로 바꾸는 유일한 지점.
// 역할분담 §2.3·§5.2. Bridge(수신·JSON 파싱)와 Overlay Bus(렌더링용 상태) 사이에서
// type별 라우팅만 한다 — Unity 계약도 Overlay 계약도 여기 밖에서 재해석하지 않는다.
// 레이어를 여는 길은 worldScreen 하나다 — F 를 연달아 눌러도 창이 겹치지 않는 것이 거기서 나온다.
import { subscribeBoothInteract } from '../../unity/bridge/events';
import type { UnityInteractEvent } from '../../unity/bridge/events';
import { toAiChatPayload } from '../../unity/bridge/events';
import { openManagement, openVisitorOverlay } from '../world/model/worldScreen';

// events.ts의 onBoothInteract는 JSON.parse 결과를 타입 단언만 하고 런타임 검증을 안 한다 —
// 여기 없는 type이 실제로 올 수 있다(신규 상호작용 추가·구버전 Unity). 조용히 무시해 전방 호환한다.
function dispatch(event: UnityInteractEvent): void {
  switch (event.type) {
    case 'BOOTH_LAPTOP_INTERACT':
      openVisitorOverlay('LAPTOP', { boothId: event.boothId, objectId: event.objectId });
      return;
    case 'BOOTH_PROJECT_INTERACT':
      // 계약 확정(#110 note 2754197) — Unity 송신부(-343)는 미구현이나 payload 는 고정됐다.
      // 송신부가 오면 이 case 가 그대로 실경로가 된다.
      openVisitorOverlay('PROJECT', { boothId: event.boothId, objectId: event.objectId });
      return;
    case 'AI_AGENT_INTERACT':
      openVisitorOverlay('AI_CHAT', toAiChatPayload(event));
      return;
    case 'BOOTH_GAME_INTERACT':
      openVisitorOverlay('GAME', { boothId: event.boothId, objectId: event.objectId, configId: event.configId });
      return;
    case 'BOOTH_SURVEY_INTERACT':
      // surveyId 는 싣지 않는다 — boothId 는 설문을 resolve 하기 위한 context 이고,
      // 실제 식별자 해석은 adapter 몫이다 (S15P21A604-415).
      openVisitorOverlay('SURVEY', { boothId: event.boothId, objectId: event.objectId });
      return;
    case 'WORLD_MANAGEMENT_INTERACT':
      // Overlay Bus 가 아니다. Bus 는 "부스 오브젝트를 열어 본다" 는 Visitor 층이고,
      // 관리 화면은 사용자가 직접 여는 별도 레이어다(gameClientUi) — !240 설계 그대로.
      // 대상 부스는 이 화면이 GET /booths/mine 으로 resolve 한다.
      openManagement();
      return;
    default:
      return;
  }
}

export function initInteractionDispatcher(): () => void {
  return subscribeBoothInteract(dispatch);
}
