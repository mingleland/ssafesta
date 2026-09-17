// 월드 채팅 말풍선 — 받은 메시지를 Unity 로 중계한다 (S15P21A604-851, GitLab #203).
//
// React 채팅 레이어는 화면 왼쪽 아래에 로그를 그리고, Unity 는 같은 말을 **말한 사람 머리 위**에
// 잠깐 띄운다. 둘은 같은 사건을 다른 자리에 보여 주는 것이라 소스가 하나여야 한다 — 그래서 FE 가
// 소켓에서 받은 payload 를 그대로 넘긴다.
//
// **payload 를 새로 만들지 않는다.** 서버가 `/topic/world/chat` 으로 방송한 문자열을 손대지 않고
// 그대로 보낸다. 게임 파트가 읽는 것은 `senderUserId`·`nickname`·`content` 셋뿐이고 나머지는
// 무시한다. 특히 표시 시간을 FE 가 실어 보내지 않는다 — 월드가 글자 수로 정하므로(3초 + 글자×0.09초,
// 3~7초 클램프) FE 가 정하면 같은 말이 화면마다 다르게 남는다(#203 계약).
//
// `senderUserId` 는 Unity 의 `NetworkPlayer.UserId` 와 같은 값이라 매핑 테이블이 없다. 그 값은
// 입장 승인 때 서버가 넣고 쓰기 권한도 서버뿐이라 클라이언트가 다른 사람을 사칭할 수 없다.
import { getReadyUnityInstance } from './sessionManager';
import type { UnityInstance } from './types';

/** `RuntimeInitializeOnLoadMethod` 로 자동 등록되는 `DontDestroyOnLoad` 오브젝트 — 씬·타이밍 무관 */
export const WORLD_CHAT_BRIDGE_OBJECT = 'WorldChatBridge';

export function receiveChat(instance: UnityInstance, payload: string): void {
  instance.SendMessage(WORLD_CHAT_BRIDGE_OBJECT, 'ReceiveChat', payload);
}

/**
 * 인스턴스가 떠 있을 때만 넘긴다. 없으면(mock 월드 · boot 전 · 월드 미진입) 조용히 건너뛰고
 * false 를 돌려준다 — **큐를 두지 않는다.** 저장이 없는 채팅이라 늦게 도착한 말풍선은 의미가 없고,
 * 이미 지나간 말을 나중에 띄우면 오히려 현재 대화와 어긋난다(`boothLayoutBridge` 와 같은 판단).
 */
export function notifyWorldChatMessage(payload: string): boolean {
  const instance = getReadyUnityInstance();
  if (instance === null) return false;
  receiveChat(instance, payload);
  return true;
}

