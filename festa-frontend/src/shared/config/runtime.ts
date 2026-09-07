// 배포 이미지가 런타임에 주입하는 설정을 읽는 단일 창구 — 소비처가 각자 해석하면 fallback 규칙이 갈라진다.
// 컨테이너 entrypoint가 PUBLIC_API_BASE_URL로 /runtime-config.js를 다시 써 window.__FESTA_CONFIG__를 채운다.
// 출처: docs/LJH/27_FE_Docker_설계.md §2 (S15P21A604-254)

// 런타임 주입 값의 전체 목록 — entrypoint(docker/40-runtime-config.sh)가 쓰는 키와 1:1 이다.
// unityBuildBase: Unity WebGL 산출물 base URL(-427, #127).
// boothAssetBase: Booth 2.5D 에셋 base URL(-473). Unity 빌드와 base 를 공유하지 않으므로 키가 따로다.
export interface RuntimeAssetConfig {
  apiBaseUrl?: string;
  unityBuildBase?: string;
  boothAssetBase?: string;
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
export function resolveApiBaseUrl(runtimeValue: string | undefined, buildValue: string | undefined): string {
  if (runtimeValue) return runtimeValue;
  return buildValue ?? '';
}

export function apiBaseUrl(): string {
  return resolveApiBaseUrl(runtimeValue('apiBaseUrl'), import.meta.env.VITE_API_BASE_URL);
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
