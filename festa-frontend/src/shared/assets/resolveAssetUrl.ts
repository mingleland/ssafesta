// Runtime Asset Delivery 의 공통 규칙 — "base URL + manifest 상대경로 → 절대 URL" 하나로 Unity 빌드
// (unity/host/resolver.ts)와 향후 Booth 에셋 manifest 가 같은 해석을 쓴다. 각 소비처가 문자열을 이어 붙이면
// FE 오리진 기준 해석·이중 슬래시·절대 URL 통과 같은 규칙이 갈라진다(#128 §4 가 그 실사례다).
// 출처: S15P21A604-427, GitLab #127·#128 §4

// base 는 절대 URL(https://cdn/unity/)이거나 오리진 상대 경로(/unity/)다. 상대 경로는 현재 문서 오리진 기준으로
// 절대화한다. 끝 슬래시가 없으면 마지막 세그먼트가 디렉터리로 취급되지 않으므로 붙인다.
export function normalizeAssetBase(base: string, origin: string = window.location.origin): URL {
  const trimmed = base.trim();
  if (trimmed === '') throw new Error('asset base URL 이 비어 있다');
  const withSlash = trimmed.endsWith('/') ? trimmed : `${trimmed}/`;
  try {
    return new URL(withSlash, origin);
  } catch {
    throw new Error(`asset base URL 을 해석할 수 없다: ${base}`);
  }
}

// manifest 안의 경로를 base 기준으로 푼다. 이미 절대 URL(스킴 포함)이면 그대로 통과한다 — manifest 생성자가
// 절대 URL 을 쓰기로 바꿔도 소비처 코드가 안 바뀐다.
export function resolveAssetUrl(path: string, base: URL): string {
  return new URL(path, base).toString();
}
