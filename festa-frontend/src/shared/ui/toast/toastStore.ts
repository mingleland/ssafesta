// Toast — 레이아웃 밖 공통 알림층 (S15P21A604-465).
//
// 정본이 자리를 이미 정해 뒀다: 소관은 React(ui-ownership-matrix), 위치는 우상단
// Consultation Quick Access 아래(screen-specifications A-1 도식), 계층은
// world < screen-ui < overlay-dim < overlay < **toast** < system(visual-dna).
// 여기서 새로 정하는 것은 수명·중복·상한뿐이다.
//
// 왜 화면 안 알림으로 충분하지 않은가. 로그인 패널에 alert 를 붙이면 패널이 자란다 —
// vh 500 에서 상단 notice +49px, 하단 오류 +68px 라 둘이 겹치면 푸터를 90px 뚫는다.
// 하나를 흐름 밖으로 빼도 두 개가 겹치면 같은 일이 반복된다. 알림을 레이아웃에서
// 완전히 떼어 놓아야 화면 높이와 무관해진다.
import { useSyncExternalStore } from 'react';

export type ToastKind = 'error' | 'info' | 'success';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/**
 * 자동 소멸 시간. **error 는 없다** — 서버 오류는 읽고 닫는 것이지 놓치면 안 되는 것이라
 * 4초 뒤에 사라지면 사용자가 원인을 모른 채 남는다.
 */
const AUTO_DISMISS_MS: Partial<Record<ToastKind, number>> = {
  info: 4_000,
  success: 4_000,
};

/** HUD 총 점유 15% 미만(world-reference-brief)을 지키기 위한 상한 */
export const MAX_TOASTS = 3;

let state: readonly Toast[] = [];
let nextId = 1;
const listeners = new Set<() => void>();
const timers = new Map<number, ReturnType<typeof setTimeout>>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(next: readonly Toast[]): void {
  state = next;
  emit();
}

function clearTimer(id: number): void {
  const timer = timers.get(id);
  if (timer === undefined) return;
  clearTimeout(timer);
  timers.delete(id);
}

/**
 * 알림을 띄운다. 같은 내용이 이미 떠 있으면 **쌓지 않고 그것을 그대로 둔다** —
 * 게스트 버튼을 연타하면 같은 오류가 세 개 쌓이는데, 그건 정보가 아니라 소음이다.
 */
export function showToast(message: string, kind: ToastKind = 'info'): number {
  const existing = state.find((t) => t.message === message && t.kind === kind);
  if (existing !== undefined) return existing.id;

  const toast: Toast = { id: nextId++, kind, message };
  // 상한을 넘으면 가장 오래된 것을 밀어낸다. 최신 알림이 밀려나면 방금 한 행동의 결과를 놓친다.
  const kept = state.length >= MAX_TOASTS ? state.slice(state.length - MAX_TOASTS + 1) : state;
  for (const dropped of state.slice(0, state.length - kept.length)) clearTimer(dropped.id);
  setState([...kept, toast]);

  const ttl = AUTO_DISMISS_MS[kind];
  if (ttl !== undefined) {
    timers.set(
      toast.id,
      setTimeout(() => dismissToast(toast.id), ttl),
    );
  }
  return toast.id;
}

export function dismissToast(id: number): void {
  clearTimer(id);
  if (!state.some((t) => t.id === id)) return;
  setState(state.filter((t) => t.id !== id));
}

export function subscribeToasts(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getToastsSnapshot(): readonly Toast[] {
  return state;
}

export function useToasts(): readonly Toast[] {
  return useSyncExternalStore(subscribeToasts, getToastsSnapshot, getToastsSnapshot);
}

// 테스트 전용
export function __resetToastsForTests(): void {
  for (const id of timers.keys()) clearTimer(id);
  state = [];
  nextId = 1;
  listeners.clear();
}
