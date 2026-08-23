// Interaction Dispatcher — Unity 이벤트를 FE 의미(Overlay 요청)로 바꾸는 유일한 지점.
// 역할분담 §2.3·§5.2. Bridge(수신·JSON 파싱)와 Overlay Bus(렌더링용 상태) 사이에서
// type별 라우팅만 한다 — Unity 계약도 Overlay 계약도 여기 밖에서 재해석하지 않는다.
import { subscribeBoothInteract } from '../../unity/bridge/events';
import type { BoothInteractEvent } from '../../unity/bridge/events';
import { toAiChatPayload } from '../../unity/bridge/events';
import { openOverlay } from '../../shared/types/overlay';

// events.ts의 onBoothInteract는 JSON.parse 결과를 타입 단언만 하고 런타임 검증을 안 한다 —
// 여기 없는 type이 실제로 올 수 있다(신규 상호작용 추가·구버전 Unity). 조용히 무시해 전방 호환한다.
function dispatch(event: BoothInteractEvent): void {
  switch (event.type) {
    case 'BOOTH_LAPTOP_INTERACT':
      openOverlay('LAPTOP', { boothId: event.boothId, objectId: event.objectId, url: event.url });
      return;
    case 'AI_AGENT_INTERACT':
      openOverlay('AI_CHAT', toAiChatPayload(event));
      return;
    case 'BOOTH_GAME_INTERACT':
      openOverlay('GAME', { boothId: event.boothId, objectId: event.objectId, configId: event.configId });
      return;
    default:
      return;
  }
}

export function initInteractionDispatcher(): () => void {
  return subscribeBoothInteract(dispatch);
}
