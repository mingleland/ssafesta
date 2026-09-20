// 브라우저 전체화면 (S15P21A604-733) — CSS 로 화면을 채우는 것이 아니라 **Fullscreen API** 다.
// 요구는 F11 상태이고, 그 상태는 브라우저만 만들 수 있다.
//
// 진입 경로는 **HUD 우상단 토글 하나**다. 로그인·월드 입장에서 자동으로 걸지 않는다 — 사용자가
// 요청하지 않은 전체화면은 화면이 갑자기 바뀌는 것으로만 보인다.
import { useSyncExternalStore } from 'react';

export function isFullscreen(): boolean {
  return typeof document !== 'undefined' && document.fullscreenElement != null;
}

/** 성공 여부를 돌려준다 — 브라우저가 거부해도 던지지 않는다. 부르는 쪽이 진행을 멈추면 안 된다. */
export async function enterFullscreen(): Promise<boolean> {
  const el = typeof document === 'undefined' ? null : document.documentElement;
  if (el === null || typeof el.requestFullscreen !== 'function') return false;
  try {
    await el.requestFullscreen();
    return true;
  } catch {
    return false;
  }
}

export async function exitFullscreen(): Promise<void> {
  if (typeof document === 'undefined' || typeof document.exitFullscreen !== 'function') return;
  if (!isFullscreen()) return;
  try {
    await document.exitFullscreen();
  } catch {
    // 이미 빠져나왔거나 브라우저가 거부했다. 상태는 fullscreenchange 가 정본이다.
  }
}

export async function toggleFullscreen(): Promise<void> {
  if (isFullscreen()) await exitFullscreen();
  else await enterFullscreen();
}

/** 실제 상태의 정본은 브라우저다 — F11·ESC 로 빠져나가도 따라간다. */
export function subscribeFullscreen(listener: () => void): () => void {
  if (typeof document === 'undefined') return () => {};
  document.addEventListener('fullscreenchange', listener);
  return () => document.removeEventListener('fullscreenchange', listener);
}

export function useFullscreen(): boolean {
  return useSyncExternalStore(subscribeFullscreen, isFullscreen, () => false);
}
