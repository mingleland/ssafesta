// Booth 2.5D 에셋 manifest — assetCode → GLB URL·실측 치수 (S15P21A604-473).
//
// manifest 는 `node tools/assets/build-booth-assets.mjs` 가 만든다. 저장소에는 없다 —
// 벤더 에셋 웹 재배포 확인 전이라 산출물을 커밋하지 않기 때문이다. 그래서 **없는 것이 정상**이고,
// 없으면 렌더러는 파라메트릭 박스로 그린다.
//
// 다만 "없음" 과 "있는데 실패" 는 다르게 다룬다. 404 는 조용히 넘기고, 그 밖의 실패는
// 이유를 남긴다 — 조용히 기본값으로 되돌아가는 것이 T-24 의 원인이었다.
import type { ObjectType } from '../../../entities/layout/types';

export interface BoothAssetBounds {
  min: [number, number, number];
  max: [number, number, number];
}

export interface BoothAssetEntry {
  assetCode: string;
  objectType: ObjectType;
  /** assetCode 없이 그 타입으로 놓였을 때 쓸 자산인가 (Unity BoothObjectRegistry 의 "타입 기본" 과 같은 뜻) */
  typeDefault: boolean;
  /** manifest 기준 상대 경로. BASE_URL 로 푼다 */
  url: string;
  bytes: number;
  triangles: number;
  /** 정규화 후 실측 bbox(m) — 계약 AABB 와 대조하는 근거 */
  bounds: BoothAssetBounds;
  source: {
    fbx: string;
    unitScale: number;
    upAxis: string;
    rawBounds: BoothAssetBounds;
  };
}

export interface BoothAssetManifest {
  version: number;
  generatedAt: string;
  assets: BoothAssetEntry[];
}

export const BOOTH_ASSET_MANIFEST_URL = 'assets/booth/manifest.json';
const SUPPORTED_VERSION = 1;

/** BASE_URL 기준으로 푼다 — 앱이 하위 경로에 배포돼도 같은 코드가 동작해야 한다 */
export function assetUrl(relative: string): string {
  const base = import.meta.env.BASE_URL || '/';
  return `${base.endsWith('/') ? base : `${base}/`}${relative.replace(/^\//, '')}`;
}

export type ManifestResult =
  | { kind: 'ready'; manifest: BoothAssetManifest }
  | { kind: 'absent' }
  | { kind: 'error'; reason: string };

export async function fetchBoothAssetManifest(signal?: AbortSignal): Promise<ManifestResult> {
  let response: Response;
  try {
    response = await fetch(assetUrl(BOOTH_ASSET_MANIFEST_URL), { signal });
  } catch (error) {
    return { kind: 'error', reason: error instanceof Error ? error.message : String(error) };
  }
  if (response.status === 404) return { kind: 'absent' };
  if (!response.ok) return { kind: 'error', reason: `HTTP ${response.status}` };

  let parsed: unknown;
  try {
    parsed = await response.json();
  } catch {
    // dev 서버는 없는 정적 파일에 index.html 을 돌려주기도 한다 — 그것도 "없음" 이다
    return { kind: 'absent' };
  }
  if (!isManifest(parsed)) return { kind: 'error', reason: 'manifest 형식이 아니다' };
  if (parsed.version !== SUPPORTED_VERSION) {
    return { kind: 'error', reason: `지원하지 않는 manifest version ${parsed.version}` };
  }
  return { kind: 'ready', manifest: parsed };
}

function isManifest(value: unknown): value is BoothAssetManifest {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.version === 'number' && Array.isArray(v.assets);
}

/**
 * 오브젝트에 붙일 에셋을 고른다.
 *
 * 1순위는 assetCode 다 — 사용자가 고른 외형이다.
 * 없으면 그 타입의 **기본으로 표시된** 자산을 쓴다. "후보가 하나뿐이면 그것" 같은 규칙은 쓰지
 * 않는다 — 두 번째 자산이 들어오는 순간 아무 관계 없는 모델이 기본 자리를 차지하고, 그게
 * Unity 쪽에서 T-148 로 터진 방식이다. 기본은 선언으로만 정한다.
 * 기본이 여럿이면 고르지 않는다(선언이 잘못된 것이므로 조용히 하나를 집지 않는다).
 */
export function pickAsset(
  assets: BoothAssetEntry[],
  object: { type: ObjectType; assetCode?: string },
): BoothAssetEntry | undefined {
  if (object.assetCode !== undefined) {
    const byCode = assets.find((a) => a.assetCode === object.assetCode);
    if (byCode !== undefined) return byCode;
  }
  const defaults = assets.filter((a) => a.objectType === object.type && a.typeDefault);
  return defaults.length === 1 ? defaults[0] : undefined;
}

/** manifest 실측 bbox 와 계약 AABB 의 축별 차이(m). 0 에 가까울수록 파이프라인이 맞다 */
export function boundsDelta(
  entry: BoothAssetEntry,
  contract: { min: { x: number; y: number; z: number }; max: { x: number; y: number; z: number } },
): [number, number, number] {
  const size = (b: BoothAssetBounds, i: number) => b.max[i] - b.min[i];
  return [
    Math.abs(size(entry.bounds, 0) - (contract.max.x - contract.min.x)),
    Math.abs(size(entry.bounds, 1) - (contract.max.y - contract.min.y)),
    Math.abs(size(entry.bounds, 2) - (contract.max.z - contract.min.z)),
  ];
}
