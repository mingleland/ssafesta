// 로컬 개발용 가짜 Unity 로더 — 빌드 URL도 실 인스턴스도 만들지 않는다. progress를 흉내내고
// 짧은 지연 후 window.FestaUnity.onWorldGateReady를 직접 호출해 실 Unity의 신호를 재현한다.
// devtools에서 window.__unityMock으로 실패·무응답 시나리오를 켤 수 있다(mock lease sentinel과 같은 패턴).
// VITE_USE_MOCK=true일 때 loader.ts 대신 이 모듈을 쓴다(선택은 loader.select.ts에서 한다).

import type { UnityInstance, UnityProgressListener } from './types';

declare global {
  interface Window {
    __unityMock?: {
      forceLoadFail?: boolean; // true면 로드 자체를 실패시킨다(§검증 시나리오3)
      suppressGateReady?: boolean; // true면 onWorldGateReady를 호출하지 않는다 — 타임아웃 재현(§검증 시나리오2)
    };
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function loadUnityBuild(
  _canvas: HTMLCanvasElement,
  onProgress: UnityProgressListener,
): Promise<UnityInstance> {
  if (window.__unityMock?.forceLoadFail) {
    throw new Error('[unity-mock] 강제 로드 실패 (devtools window.__unityMock.forceLoadFail)');
  }

  for (const p of [0.2, 0.5, 0.8, 1]) {
    await delay(80);
    onProgress(p);
  }

  if (!window.__unityMock?.suppressGateReady) {
    // 실 Unity는 게이트 씬 첫 렌더 후 스스로 이 콜백을 부른다 — mock은 생성 직후로 흉내낸다.
    void delay(80).then(() => window.FestaUnity?.onWorldGateReady?.());
  }

  return {
    SendMessage: () => {},
    SetFullscreen: () => {},
    Quit: () => delay(50),
  };
}
