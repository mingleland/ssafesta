// 배포 이미지가 런타임에 주입하는 설정을 읽는 단일 창구 — 소비처가 각자 해석하면 fallback 규칙이 갈라진다.
// 컨테이너 entrypoint가 PUBLIC_API_BASE_URL로 /runtime-config.js를 다시 써 window.__FESTA_CONFIG__를 채운다.
// 출처: docs/LJH/27_FE_Docker_설계.md §2 (S15P21A604-254)

declare global {
  interface Window {
    __FESTA_CONFIG__?: { apiBaseUrl?: string };
  }
}

// 판정만 순수 함수로 뺀다 — 컴포넌트 테스트 환경(G-4)이 develop에 아직 없어 window를 세우지 않고 검증한다.
// 런타임 값이 비어 있으면(주입 안 함·빈 문자열) 빌드타임 값으로 내려간다. 빌드타임도 없으면 상대 경로다.
export function resolveApiBaseUrl(runtimeValue: string | undefined, buildValue: string | undefined): string {
  if (runtimeValue) return runtimeValue;
  return buildValue ?? '';
}

export function apiBaseUrl(): string {
  const runtimeValue = typeof window === 'undefined' ? undefined : window.__FESTA_CONFIG__?.apiBaseUrl;
  return resolveApiBaseUrl(runtimeValue, import.meta.env.VITE_API_BASE_URL);
}
