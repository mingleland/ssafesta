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

export function getOverlay(): OverlayRequest | null {
  return current;
}

export function subscribeOverlay(listener: OverlayListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
