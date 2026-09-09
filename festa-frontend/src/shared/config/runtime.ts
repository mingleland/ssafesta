// 배포 이미지가 런타임에 주입하는 설정을 읽는 단일 창구 — 소비처가 각자 해석하면 fallback 규칙이 갈라진다.
// 컨테이너 entrypoint가 PUBLIC_API_BASE_URL로 /runtime-config.js를 다시 써 window.__FESTA_CONFIG__를 채운다.
// 출처: docs/LJH/27_FE_Docker_설계.md §2 (S15P21A604-254)

// 런타임 주입 값의 전체 목록.
// unityBuildBase: Unity WebGL 산출물 base URL(-427, #127).
// aiApiBaseUrl: AI(FastAPI) 서버 base URL(spec 008). Spring 과 별도 배포라 키가 따로다.
// boothAssetBase: Booth 2.5D 에셋 base URL(-473). Unity 빌드와 base 를 공유하지 않으므로 키가 따로다.
// authBaseUrl: OAuth·게스트 계열 전용 base(-564). 나머지 API 와 갈라 두는 이유는 아래 authBaseUrl() 참고.
//
// entrypoint(docker/40-runtime-config.sh)가 실제로 쓰는 키는 apiBaseUrl·unityBuildBase 둘뿐이다 —
// 나머지 둘(+authBaseUrl)은 배포 주입 경로가 아직 없다. 그 공백은 인프라 정렬(-564 P9)에서 다룬다.
export interface RuntimeAssetConfig {
  apiBaseUrl?: string;
  unityBuildBase?: string;
  aiApiBaseUrl?: string;
  boothAssetBase?: string;
  authBaseUrl?: string;
}

declare global {
  interface Window {
    __FESTA_CONFIG__?: RuntimeAssetConfig;
  }
}

function runtimeValue(key: keyof RuntimeAssetConfig): string | undefined {
  return typeof window === 'undefined' ? undefined : window.__FESTA_CONFIG__?.[key];
}

// 판정만 순수 함수로 뺀다 — 컴포넌트 테스트 환경(G-4)이 develop에 아직 없어 window를 세우지 않고 검증한다.
// 런타임 값이 비어 있으면(주입 안 함·빈 문자열) 빌드타임 값으로 내려간다. 빌드타임도 없으면 상대 경로다.
// 소비처는 aiApiBaseUrl·unityBuildBase 다 — 그쪽은 `''`(미설정)이 의미를 갖는다. Unity WebGL base 는
// 빈 값일 때 소비처가 이름으로 실패해야 하고(unity/host/resolver.ts), 그 성질을 바꾸면 안 된다.
// Spring API base 는 이 규칙을 쓰지 않는다 — resolveHostApiBaseUrl 을 본다.
export function resolveApiBaseUrl(runtimeValue: string | undefined, buildValue: string | undefined): string {
  if (runtimeValue) return runtimeValue;
  return buildValue ?? '';
}

function currentOrigin(): string {
  return typeof window === 'undefined' ? '' : window.location.origin;
}

/**
 * Spring API base 판정 (S15P21A604-564 — FE Host Gateway).
 *
 * `resolveApiBaseUrl` 과 다른 점은 **빈 결과를 내지 않는다**는 것 하나다. 종전에는 아무 설정이
 * 없으면 `''` 를 돌려줬고 React 는 그것으로 same-origin 상대 요청을 했지만, 같은 값을 읽는
 * Unity 는 `''` 를 "주입 없음" 으로 읽어 **빌드 타임 Prod(`https://api.ssafesta.world`)로 조용히
 * 내려갔다** (jslib `FestaHostApiBaseUrl` → `HostRuntimeConfig` → `ApiServices.Init`). 로컬에서
 * 월드 진입이 말없이 실패하던 경로가 이것이다.
 *
 * 그래서 미설정을 **FE 오리진**으로 해석한다. React 에게는 same-origin 이라 동작이 같고, Unity
 * 에게는 절대 URL 이 넘어가 fallback 분기 자체를 타지 않는다. 상대 경로 주입(`/__dev/api`)도
 * 오리진 기준으로 절대화해 같은 성질을 준다 — Unity 는 상대 URL 을 받지 않는다.
 *
 * `origin` 이 빈 문자열인 환경(vitest node env·SSR)에서는 절대화할 기준이 없으므로 주입값을 그대로
 * 돌려준다. 브라우저가 아니면 Unity 도 없다.
 */
export function resolveHostApiBaseUrl(
  runtimeValue: string | undefined,
  buildValue: string | undefined,
  origin: string,
): string {
  const configured = runtimeValue || buildValue || '';
  if (origin === '') return configured;
  return new URL(configured || '/', origin).href.replace(/\/+$/, '');
}

export function apiBaseUrl(): string {
  return resolveHostApiBaseUrl(
    runtimeValue('apiBaseUrl'),
    import.meta.env.VITE_API_BASE_URL,
    currentOrigin(),
  );
}

/**
 * Unity 가 읽을 `window.__FESTA_CONFIG__.apiBaseUrl` 을 채운다 — **Unity boot 전에** 불러야 한다.
 *
 * Unity 쪽 `HostRuntimeConfig` 는 이 값을 **한 번만 읽고 캐시**한다. 인스턴스가 선 뒤에 바꾸면
 * 아무 효과가 없다. 호출 지점은 `unity/host/loader.ts` 의 `loadUnityBuild` 최상단 하나다.
 *
 * 여기서 값을 만들지 않고 `apiBaseUrl()` 을 그대로 쓰는 것이 요점이다 — React 와 Unity 가 같은
 * 판정을 공유해야 "FE 는 되는데 Unity 만 다른 서버로 간다" 가 생기지 않는다.
 */
export function publishHostApiBaseUrl(): string {
  const resolved = apiBaseUrl();
  if (typeof window === 'undefined') return resolved;
  const config: RuntimeAssetConfig = (window.__FESTA_CONFIG__ ??= {});
  if (config.apiBaseUrl !== resolved) {
    config.apiBaseUrl = resolved;
    console.info(`[RuntimeConfig] apiBaseUrl 확정 — ${resolved}`);
  }
  return resolved;
}

/**
 * OAuth·게스트 계열 전용 base (S15P21A604-564).
 *
 * **일반 API 와 같은 게이트웨이에 묶을 수 없다.** BE 가 내리는 인증 쿠키 셋이 host-only 이고
 * Path 가 좁기 때문이다 — `oauth_handoff`(Path `/api/v1/auth/oauth/complete`)·
 * `refresh_token`(Path `/api/v1/auth/refresh`)·`JSESSIONID`. provider 에 등록된 redirect URI 가
 * 콜백 호스트를 고정하므로, 인가 요청·콜백·complete·refresh 가 **전부 같은 호스트**여야 쿠키가
 * 이어진다. 게스트도 같은 base 를 써야 한다 — `POST /auth/guest` 도 `refresh_token` 을 내리는데
 * 회원과 다른 호스트에 놓이면 `refresh()` 하나가 둘을 만족할 수 없다.
 *
 * 미설정이면 `apiBaseUrl()` 과 같다 — 게이트웨이가 없는 배포(현행 Demo/Prod)에서는 API 호스트가
 * 곧 인증 호스트라 값이 이미 맞다. 로컬처럼 둘이 갈리는 환경에서만 지정한다.
 */
export function authBaseUrl(): string {
  const configured = runtimeValue('authBaseUrl') || import.meta.env.VITE_AUTH_BASE_URL;
  if (!configured) return apiBaseUrl();
  const origin = currentOrigin();
  return origin === '' ? configured : new URL(configured, origin).href.replace(/\/+$/, '');
}

// AI(FastAPI) 서버 base URL — Spring과 별도 배포다(spec 008 plan.md). 인프라가 same-origin
// 경로 분기(nginx)로 합칠지 별도 호스트로 둘지 아직 미정이라 unityBuildBase와 같은 방식으로
// 런타임 주입값을 정본으로 둔다 — 값이 정해지면 이 accessor는 그대로, 배포 설정만 바뀐다.
export function aiApiBaseUrl(): string {
  return resolveApiBaseUrl(runtimeValue('aiApiBaseUrl'), import.meta.env.VITE_AI_API_BASE_URL);
}

// Unity WebGL 빌드 base URL — 정본은 런타임 주입(PUBLIC_UNITY_BUILD_BASE)이고, VITE_UNITY_BUILD_BASE 는
// 로컬/dev fallback 이다. 같은 FE 이미지가 local/dev/demo 에서 base 만 바꿔 뜨게 하려는 것이라 빌드 타임
// 값에 정본을 고착시키지 않는다(#127). 비어 있으면 '' — 소비처(unity/host/resolver.ts)가 이름으로 실패를 말한다.
export function unityBuildBase(): string {
  return resolveApiBaseUrl(runtimeValue('unityBuildBase'), import.meta.env.VITE_UNITY_BUILD_BASE);
}

// Booth 2.5D 에셋 base URL. 지금은 에셋이 FE 정적 자원으로 나가므로 앱 base(BASE_URL)가 기본이고,
// CDN 으로 옮기면 런타임 주입(PUBLIC_BOOTH_ASSET_BASE)만 채우면 된다 — 소비처 코드는 그대로다.
// 문서(fe-contract-boundary)가 오래 "seam 이 열려 있다" 고 적어 온 자리를 실제로 연 것이다.
export function boothAssetBase(): string {
  const configured = runtimeValue('boothAssetBase');
  if (configured) return configured;
  // dev 서버가 .generated/runtime 을 이 경로로 내보낸다(vite.config 의 ssafesta-runtime-assets).
  // 프로덕션 이미지에는 이 디렉터리가 없다 — 벤더 라이선스가 REVIEW_REQUIRED 인 동안의 경계다.
  return `${import.meta.env.BASE_URL || '/'}assets/booth-runtime/`;
}
