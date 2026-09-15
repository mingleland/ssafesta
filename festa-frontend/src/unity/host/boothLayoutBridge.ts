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
// **2026-09-15 정정 (S15P21A604-786).** 여기 오래 적혀 있던 "facade 저장은 version 을 안 바꾸므로
// 보내도 아무 일이 없다" 는 틀렸다. `WorldBoothPublishedBootstrap.RequestReload` 는 -659 이후
// 레이아웃 서명을 비교하기 **전에** `BoothSlotDirectory.Invalidate()` · `BoothSignPresenter.Refresh`
// · `BoothFacadePresenter.Refresh` 를 무조건 실행한다. 간판 라벨 우선순위가
// `facade.signText → boothName → project.name → "N번 부스"` 라 **부스 이름 변경도 이 경로로 반영된다**.
//
// 즉 게시본을 바꾸는 mutation 뿐 아니라 **facade 저장에도 걸어야 한다.** 다시 짓기를 건너뛰는 것은
// 레이아웃 rebuild 뿐이고, 간판·외벽은 매번 다시 읽는다.
// draft 저장은 여전히 대상이 아니다 — 게시 전이라 월드에 보일 것이 없다.
import { getReadyUnityInstance } from './sessionManager';
import type { UnityInstance } from './types';
import type { QueryClient } from '@tanstack/react-query';
import type { MyBooth } from '../../entities/booth/types';

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

/**
 * 지금 소유 중인 부스의 슬롯을 알린다 — 호출부가 slotId 를 스스로 찾지 않게 한다.
 *
 * slotId 는 `useOwnerGate` 가 채운 `['my-booth']` 캐시에 있다. 이 조회를 부르는 쪽마다 복제하면
 * 같은 query key 문자열이 여러 자리에 박힌다 — `usePublish` 와 `FacadePanel` 이 이 함수를 공유한다.
 *
 * 캐시에 없으면(mock 월드 · 게이트 통과 전) 조용히 건너뛴다. 다음 월드 진입이 최신본을 읽는다.
 */
export function notifyCurrentBoothSlotChanged(queryClient: QueryClient): boolean {
  const slotId = queryClient.getQueryData<MyBooth>(['my-booth'])?.lease?.slotId;
  if (slotId === undefined) return false;
  return notifyBoothSlotChanged(slotId);
}
