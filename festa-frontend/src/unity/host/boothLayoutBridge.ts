// FE → Unity: "이 슬롯의 게시본이 바뀌었다" (S15P21A604-644).
// 수신부는 Unity `BoothLayoutBridge`(festa-unity Integration/BoothLayoutBridge.cs, -508 develop 도달) —
// `AuthBridge`·`AudioBridge`·`WorldUiBridge` 와 같은 자동 등록 오브젝트다(씬 무관):
//   SendMessage('BoothLayoutBridge', 'ReloadBoothSlot', '7')
//
// 왜 필요한가: Unity 는 월드 진입 시 12슬롯 게시본을 한 번만 조회한다. -620 이전에는 /app/booths 로
// 나갔다 오면 Unity 가 재부팅돼 다시 읽었는데, 상주 월드가 Unity 를 살려두니 임대·게시 뒤에도 옛
// 모습이 남는다(2026-09-11 5173 실측 — 6번 임대 뒤 월드에서 접근 불가). 게임 파트는 QA 09-08 #11 로
// 이 수신부를 이미 만들어 뒀고 FE 호출만 0건이었다(docs/LJH/24_작업일지.md:69).
//
// Unity 는 받은 슬롯의 게시본 version 을 다시 읽어 **바뀌었을 때만** 다시 짓는다
// (WorldBoothPublishedBootstrap.RequestReload). 그래서 보내는 쪽도 게시본을 바꾸는 mutation 에만
// 건다 — draft 저장·facade 저장은 version 을 안 바꾸므로 보내도 아무 일이 없다.
import { getReadyUnityInstance } from './sessionManager';
import type { UnityInstance } from './types';

export const BOOTH_LAYOUT_BRIDGE_OBJECT = 'BoothLayoutBridge';

export function reloadBoothSlot(instance: UnityInstance, slotId: number): void {
  instance.SendMessage(BOOTH_LAYOUT_BRIDGE_OBJECT, 'ReloadBoothSlot', String(slotId));
}

/**
 * 인스턴스가 떠 있을 때만 알린다. 없으면(mock 월드 · boot 전 · 월드 미진입) 아무 일도 하지 않고
 * false 를 돌려준다 — **큐를 두지 않는다.** 다음 월드 진입이 어차피 최신 게시본을 읽는다.
 */
export function notifyBoothSlotChanged(slotId: number): boolean {
  const instance = getReadyUnityInstance();
  if (instance === null) return false;
  reloadBoothSlot(instance, slotId);
  return true;
}
