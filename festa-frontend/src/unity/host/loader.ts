// 실 Unity WebGL 로더 — resolver로 빌드 URL 4종을 받아 로더 스크립트를 주입하고 인스턴스를 만든다
// 출처: 013a WebGL Host 계획 B-1

import { resolveBuildDescriptor } from './resolver';
import type { UnityInstance, UnityProgressListener } from './types';

declare global {
  interface Window {
    // Unity 로더 스크립트(<script src=loaderUrl>)가 로드되면서 전역에 노출하는 함수 — 문서화된
    // 공개 API. 실 스크립트는 저장소에 없어(빌드 산출물) 타입만 선언한다.
    createUnityInstance?: (
      canvas: HTMLCanvasElement,
      config: Record<string, unknown>,
      onProgress?: UnityProgressListener,
    ) => Promise<UnityInstance>;
  }
}

function injectLoaderScript(loaderUrl: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = loaderUrl;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Unity 로더 스크립트를 불러오지 못했습니다: ${loaderUrl}`));
    document.body.appendChild(script);
  });
}

export async function loadUnityBuild(
  canvas: HTMLCanvasElement,
  onProgress: UnityProgressListener,
): Promise<UnityInstance> {
  const descriptor = await resolveBuildDescriptor();
  await injectLoaderScript(descriptor.loaderUrl);

  if (!window.createUnityInstance) {
    throw new Error('Unity 로더 스크립트가 createUnityInstance를 노출하지 않았습니다');
  }

  return window.createUnityInstance(
    canvas,
    {
      dataUrl: descriptor.dataUrl,
      frameworkUrl: descriptor.frameworkUrl,
      codeUrl: descriptor.codeUrl,
    },
    onProgress,
  );
}
