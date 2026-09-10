// 실 Unity WebGL 로더 — resolver로 빌드 URL 4종을 받아 로더 스크립트를 주입하고 인스턴스를 만든다
// 출처: 013a WebGL Host 계획 B-1

import { resolveBuildDescriptor } from './resolver';
import { publishHostApiBaseUrl } from '../../shared/config/runtime';
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

// 렌더 해상도 상한 (S15P21A604-484 · 근거 정정 -524). Unity 는 config 에 devicePixelRatio 가
// 없으면 window.devicePixelRatio 를 그대로 쓴다 — 게임 파트 실측에서 상한 없이 표시 크기의
// **약 3.1배**를 그렸고(URP Mobile_RPAsset 의 renderScale 0.8 을 반영한 실효값), 그 상태에서
// **최악 프레임 시간**이 vsync 예산 16.7ms 를 넘겼다. GPU 시간이 아니다 — 게임 파트 계측기
// (PerfHud)에 GPU 측정은 없고 그 값은 최근 1초 창의 최악 프레임 시간이다 (#143 2026-09-08 정정).
//
// 상한 적용 후 실측 (2026-09-08, FE 임베드·게스트·보이는 탭):
//   백버퍼 2880x1418 (4.08MP) → 2160x1064 (2.30MP) · 실효 DPR 2.0 → 1.5 · 픽셀 -44%
// 1.5 가 적절한 값이라는 것도 같은 회차에 확인됐다 — 글자·외곽선이 거칠어지지 않았다.
//
// 이 값은 **createUnityInstance 를 부르는 쪽에서만** 정할 수 있다. Unity 안에서
// Screen.SetResolution 으로 낮추면 로더의 matchWebGLToCanvasSize 가 다음 프레임에 되돌려
// 백버퍼가 계속 재생성되고, 캔버스의 width/height 를 JS 로 직접 바꾸면 렌더 루프가 멈춘다
// (rAF 정지 → NGO 연결까지 끊김). 둘 다 실측으로 확인된 실패 경로다.
const MAX_DEVICE_PIXEL_RATIO = 1.5;

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
  // Unity 가 읽을 API base 를 **로더 스크립트보다 먼저** 확정한다 (S15P21A604-564).
  // Unity 쪽 HostRuntimeConfig 는 window.__FESTA_CONFIG__.apiBaseUrl 을 한 번만 읽고 캐시하므로,
  // 인스턴스가 선 뒤에 채우면 아무 효과가 없고 빌드 타임 값(Prod)이 그대로 남는다.
  publishHostApiBaseUrl();

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
      devicePixelRatio: resolveDevicePixelRatio(),
    },
    onProgress,
  );
}

/**
 * Unity 에 넘길 devicePixelRatio. 상한을 넘는 화면만 낮추고, 1 이하(또는 값을 읽을 수 없는
 * 환경)는 그대로 1 로 둔다 — 저밀도 화면을 더 흐리게 만들 이유가 없다.
 */
export function resolveDevicePixelRatio(): number {
  const ratio = typeof window === 'undefined' ? 1 : window.devicePixelRatio;
  if (!Number.isFinite(ratio) || (ratio as number) <= 0) {
    return 1;
  }
  return Math.min(ratio as number, MAX_DEVICE_PIXEL_RATIO);
}

/**
 * 화면 밀도가 바뀌면 Unity 에 다시 알린다 (S15P21A604-575, GitLab #143).
 *
 * 위 상한은 `createUnityInstance` 호출 시점의 `window.devicePixelRatio` 한 번으로 정해진다.
 * 그런데 그 값은 세션 중에 바뀐다 — 브라우저 확대/축소, 밀도가 다른 모니터로 창 이동,
 * OS 배율 변경. 부팅 값이 그대로 남으면 낮아진 화면에서는 필요 이상으로 그리고(성능),
 * 높아진 화면에서는 표시 크기보다 적게 그린다(선명도). 둘 다 재부팅 전까지 안 돌아온다.
 *
 * 실측으로 확인한 것 (2026-09-10, 현재 서빙 중인 WebGL):
 *   프레임워크는 `_JS_SystemInfo_GetPreferredDevicePixelRatio(){ return
 *   Module.matchWebGLToCanvasSize==false ? 1 : Module.devicePixelRatio || window.devicePixelRatio || 1 }`
 *   이고, Unity 는 이것을 **약 1초 주기로 계속 다시 읽는다**(부팅 1회가 아니다).
 *   `Module.devicePixelRatio = 1.0` 을 쓰자 백버퍼가 1008x575 → 672x383 으로 줄었고
 *   1.5 로 되돌리자 원래대로 돌아왔다. 렌더 루프도 멈추지 않았다.
 * 그래서 캔버스 width/height 를 직접 건드리는 실패 경로(위 주석) 없이 이 한 값만 바꾸면 된다.
 *
 * `matchMedia('(resolution: Xdppx)')` 는 지금 값에 대한 질의라 한 번 어긋나면 다시 걸어야 한다 —
 * 그래서 change 마다 새로 건다. 반환값은 해제 함수다.
 */
export function watchDevicePixelRatio(instance: UnityInstance): () => void {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return () => {};
  }

  let query: MediaQueryList | null = null;
  let stopped = false;

  const onChange = () => {
    if (stopped) return;
    const module = instance.Module;
    if (module) module.devicePixelRatio = resolveDevicePixelRatio();
    arm();
  };

  function arm() {
    query?.removeEventListener('change', onChange);
    query = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
    query.addEventListener('change', onChange);
  }

  arm();

  return () => {
    stopped = true;
    query?.removeEventListener('change', onChange);
    query = null;
  };
}
