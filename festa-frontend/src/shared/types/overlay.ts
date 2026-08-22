// Unity 3D 화면 위에 React 창을 띄우는 계약 — 어떤 타입에 어떤 데이터가 실리는지
// React 오버레이 계약 — 헌법 25조(텍스트 입력·외부 콘텐츠는 웹 레이어)
// 소비 spec: 008(AI_CHAT)·016(LAPTOP)은 P0, 010(SURVEY)·011(CONSULTATION)은 P1
export type OverlayType = 'AI_CHAT' | 'LAPTOP' | 'SURVEY' | 'CONSULTATION';

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
