// 브라우저 전체화면 (S15P21A604-733) — CSS 로 화면을 채우는 것이 아니라 **Fullscreen API** 다.
// 요구는 F11 상태이고, 그 상태는 브라우저만 만들 수 있다.
//
// **왜 로그인 클릭에서 바로 걸지 않는가.** `requestFullscreen` 은 사용자 제스처 안에서만 허용되는데
// OAuth 는 제공자 도메인으로 전체 페이지가 이동했다 돌아온다. document 가 바뀌면 전체화면은 풀리고,
// 콜백 복귀는 제스처가 아니라 다시 걸 수도 없다. 그래서 클릭에서는 **의도만** 남기고 실제 진입은
// 사용자가 로비에서 '월드 입장' 을 누른 순간에 시도한다 — Unity `WorldLoadSignal` → `onWorldLoadStart`.
//
// **캔버스의 아무 pointerdown 을 쓰지 않는다.** 캔버스 안에는 아바타 파츠 선택 같은 다른 클릭도 있어
// 커스터마이징 중에 갑자기 전체화면이 된다. React 는 캔버스 안에서 무엇을 눌렀는지 구분할 수 없다.
//
// 자동 진입은 **보조**다. 성공하든 실패하든 월드 진입을 막지 않고, 수동 토글이 정본 경로로 남는다.
import { useSyncExternalStore } from 'react';

const INTENT_KEY = 'festa.fullscreen.intent';

/** 로그인·게스트 입장 클릭에서 부른다. 여기서 전체화면을 걸지는 않는다. */
export function markFullscreenIntent(): void {
  try {
    window.sessionStorage.setItem(INTENT_KEY, '1');
  } catch {
    // 사생활 모드 등에서 sessionStorage 가 막힌다. 자동 진입만 못 하고 수동 토글은 그대로다.
  }
}

/** 한 번만 쓰인다 — 읽으면서 지운다. 다음 월드 진입에 다시 걸리면 안 된다. */
export function consumeFullscreenIntent(): boolean {
  try {
    const had = window.sessionStorage.getItem(INTENT_KEY) === '1';
    window.sessionStorage.removeItem(INTENT_KEY);
    return had;
  } catch {
    return false;
  }
}

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
