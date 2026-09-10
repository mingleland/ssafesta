// Unity 3D 화면 위에 React 창을 띄우는 계약 — 어떤 타입에 어떤 데이터가 실리는지
// React 오버레이 계약 — 헌법 25조(텍스트 입력·외부 콘텐츠는 웹 레이어)
// 소비 spec: 008(AI_CHAT)·016(LAPTOP)·020(GAME, #35 busypark)은 P0, 010(SURVEY)·011(CONSULTATION)은 P1,
// 009(PROJECT)는 방문자 전시 — Unity 송신부(-343) 확정 전에는 mock intent(openOverlay 직접 호출)로만 열린다
//
// WORLD_GUIDE·EVENT_SHOP 은 **부스에 속하지 않는다**(S15P21A604-599). 그래도 같은 Bus 에 두는 이유는
// 배타·ESC·입력 잠금·focus 반환이 전부 이 길에 붙어 있기 때문이다(worldScreen.ts). 별도 슬롯을 만들면
// 그 넷을 새로 배선해야 하고, 그것이 지금 gameClientUi 와 Bus 가 갈려 있는 값을 또 치르는 일이 된다.
export type OverlayType =
  | 'AI_CHAT'
  | 'LAPTOP'
  | 'GAME'
  | 'SURVEY'
  | 'CONSULTATION'
  | 'PROJECT'
  /** 월드 이용 안내 — FE 가 직접 연다(최초 진입 1회 + 재열람). Unity 이벤트가 없다 */
  | 'WORLD_GUIDE'
  /** 이벤트 경품 상점 — 이벤트 NPC 상호작용. Unity discriminator 가 오면 dispatcher 에 case 하나를 더한다 */
  | 'EVENT_SHOP';

export interface OverlayRequest {
  type: OverlayType;
  // 타입별 구체 payload는 각 소비 spec에서 좁힌다
  payload: unknown;
}

type OverlayListener = (request: OverlayRequest | null) => void;

let current: OverlayRequest | null = null;
const listeners = new Set<OverlayListener>();

function emit() {
  for (const listener of listeners) listener(current);
}

export function openOverlay(type: OverlayType, payload: unknown): void {
  current = { type, payload };
  emit();
}

// 016 FR-008 — 방문자가 닫고 월드로 돌아온다
export function closeOverlay(): void {
  current = null;
  emit();
}

export function subscribeOverlay(listener: OverlayListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// useSyncExternalStore의 snapshot getter — 변경이 없으면 같은 참조를 반환한다(openOverlay/closeOverlay만 current를 재할당).
export function getCurrentOverlay(): OverlayRequest | null {
  return current;
}
