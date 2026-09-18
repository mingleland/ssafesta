// 배포 직후 열려 있던 탭이 옛 chunk 를 요청해 실패했을 때 한 번만 새로고침한다 (S15P21A604-847).
//
// 배포 = nginx 이미지 교체라 이전 dist 는 사라진다. 탭이 몇 시간 열려 있는 상주 화면이라 lazy route 에
// 처음 들어갈 때 옛 해시를 요청하고, Vite 는 그 실패를 `vite:preloadError` 로 알린다. 새로고침 한 번이면
// 최신 index.html 이 새 해시를 가리키므로 복구된다.
//
// **한 번만** 이다. 같은 번들 버전에서 다시 실패하면 배포 문제가 아니라 자산 장애라 새로고침을 반복해도
// 낫지 않고 사용자 화면만 깜빡인다. 그때는 오류를 남기고 라우트 errorElement 에 맡긴다. 키를 현재 index
// 모듈 주소에 묶어 두면 새 버전이 뜬 뒤에는 자연히 다른 키가 되고, sessionStorage 라 탭을 닫으면 사라진다.

const KEY_PREFIX = 'festa-preload-reload:';

function currentBundleKey(): string {
  const entry = document.querySelector<HTMLScriptElement>('script[type="module"][src]');
  return KEY_PREFIX + (entry?.src ?? location.pathname);
}

export function handlePreloadError(event: Event, reload: () => void = () => window.location.reload()): boolean {
  event.preventDefault();
  const key = currentBundleKey();
  let seen = false;
  try {
    seen = sessionStorage.getItem(key) !== null;
    if (!seen) sessionStorage.setItem(key, String(Date.now()));
  } catch {
    // sessionStorage 미가용 — 상태를 못 남기므로 반복 새로고침 위험이 있다. 한 번도 하지 않는 쪽이 안전하다.
    seen = true;
  }
  if (seen) {
    console.error('[preload-recovery] chunk 로드가 새로고침 뒤에도 실패한다 — 자산 장애로 본다', (event as CustomEvent).detail ?? event);
    return false;
  }
  reload();
  return true;
}

export function installPreloadRecovery(): void {
  window.addEventListener('vite:preloadError', (event) => { handlePreloadError(event); });
}

