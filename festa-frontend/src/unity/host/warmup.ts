// Unity 정적 자산 warm-up (S15P21A604-430, #128·#127).
//
// 지금은 사용자가 /app/world 에 도달한 뒤에야 loader·framework·wasm·data(≈239MB)를 받기 시작한다.
// Landing·로그인·OAuth 왕복에 머무는 시간이 그대로 버려진다. 그 시간을 HTTP 캐시를 채우는 데 쓴다.
//
// 규칙 셋만 지킨다.
//   1. 인스턴스를 만들지 않는다. 세션도 만들지 않는다. 공개 정적 파일 fetch 뿐이다.
//   2. best-effort — 실패·중단·미지원이 실제 진입을 막지 않는다. 정상 loader 가 다시 받으면 그만이다.
//   3. 단계적으로 받는다. 큰 파일이 Landing 첫 페인트나 로그인 화면과 경쟁하지 않게 한다.
//
// URL 은 기존 Runtime Asset Delivery 를 그대로 쓴다(runtime-config → unityBuildBase → resolveAssetUrl).
// 새 URL 체계를 만들지 않는다.
import { unityBuildBase } from '../../shared/config/runtime';
import { normalizeAssetBase, resolveAssetUrl } from '../../shared/assets/resolveAssetUrl';
import type { UnityBuildDescriptor } from './types';

/** 사용자가 월드에 도달할 가능성이 높아지는 순서. 뒤 단계는 앞 단계를 포함한다. */
export type WarmupStage =
  /** 타이틀 화면 — 아직 아무것도 확정되지 않았다. 가장 작은 것만 */
  | 'landing'
  /** 로그인 화면에 도달 = 진입 의도가 드러났다 */
  | 'intent'
  /** 인증 완료 — 기본 목적지가 /app/world 라 사실상 확정이다 */
  | 'authenticated';

/** 단계별로 이 단계에서 *새로* 받는 것. manifest 는 어느 단계든 먼저 필요하다. */
const STAGE_KEYS: Record<WarmupStage, readonly (keyof UnityBuildDescriptor)[]> = {
  landing: ['loaderUrl'],
  intent: ['loaderUrl', 'frameworkUrl'],
  authenticated: ['loaderUrl', 'frameworkUrl', 'codeUrl', 'dataUrl'],
};

/** 회선이 아주 느리거나 데이터 절약이 켜져 있으면 큰 것은 건너뛴다 */
const LARGE_KEYS: readonly (keyof UnityBuildDescriptor)[] = ['codeUrl', 'dataUrl'];

interface ConnectionLike {
  saveData?: boolean;
  effectiveType?: string;
}

// Network Information API 는 브라우저마다 없다. 있으면 참고하고 없으면 평소대로 간다 — 필수 계약이 아니다.
function shouldSkipLarge(): boolean {
  const connection = (navigator as Navigator & { connection?: ConnectionLike }).connection;
  if (connection === undefined) return false;
  if (connection.saveData === true) return true;
  return connection.effectiveType === 'slow-2g' || connection.effectiveType === '2g';
}

// priority 는 Chrome 계열만 안다. 모르는 브라우저는 이 속성을 무시할 뿐 요청은 정상이다.
type LowPriorityInit = RequestInit & { priority?: 'high' | 'low' | 'auto' };

async function prefetch(url: string, signal: AbortSignal): Promise<void> {
  const init: LowPriorityInit = { signal, priority: 'low', credentials: 'omit' };
  const response = await fetch(url, init);
  // 본문을 끝까지 읽어야 캐시에 들어간다. 값은 버린다 — 여기서 쓰지 않는다.
  await response.arrayBuffer();
}

/**
 * 해당 단계까지의 자산을 미리 받는다. 반환값은 중단 함수다(화면을 떠나면 부른다).
 * 어떤 실패도 밖으로 던지지 않는다.
 */
export function warmUpUnityAssets(stage: WarmupStage): () => void {
  const controller = new AbortController();

  void (async () => {
    try {
      const configured = unityBuildBase();
      if (configured === '') return; // 서빙 경로 미설정 — 조용히 아무것도 하지 않는다
      if (typeof fetch !== 'function') return;

      const base = normalizeAssetBase(configured);
      const manifestResponse = await fetch(resolveAssetUrl('manifest.json', base), {
        signal: controller.signal,
        credentials: 'omit',
      });
      if (!manifestResponse.ok) return;
      const manifest = (await manifestResponse.json()) as Partial<UnityBuildDescriptor>;

      const skipLarge = shouldSkipLarge();
      for (const key of STAGE_KEYS[stage]) {
        if (controller.signal.aborted) return;
        if (skipLarge && LARGE_KEYS.includes(key)) continue;
        const relative = manifest[key];
        if (typeof relative !== 'string' || relative === '') continue;
        // 순차로 받는다 — 한꺼번에 던지면 같은 화면의 이미지·API 와 연결을 다툰다.
        await prefetch(resolveAssetUrl(relative, base), controller.signal);
      }
    } catch {
      // 네트워크 오류·중단·JSON 파싱 실패 전부 무시한다. loader 가 다시 받는다(규칙 2).
    }
  })();

  return () => controller.abort();
}
