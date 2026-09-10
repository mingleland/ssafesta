// Asset Library — runtime manifest 를 좌측 패널이 쓸 모양으로 옮긴다 (S15P21A604-551).
//
// 이 파일이 푸는 문제는 하나다: **자산 표를 손으로 들지 않는다.** 예전 팔레트는 15항을
// 하드코딩했고, 그 코드 7종은 저장소 어디에도 정의가 없었다(교집합 0). 자산이 늘 때마다
// 사람이 표를 고쳐야 했고 실제로 어긋난 채 방치됐다.
//
// 세 축을 섞지 않는다 (정본 §9):
//   Asset Domain   자산의 본질적 분류. assetCode 접두가 정한다. UI 가 바뀌어도 안 바뀐다
//   UI Category    사용자가 찾는 메뉴. **assetCode 의 FAMILY 세그먼트**가 정한다
//   ObjectType     서버가 무엇으로 취급하나. 계약 10종, 여기서 건드리지 않는다
//
// UI Category 를 manifest 에 넣지 않은 이유(정본 §10-2): 카테고리는 제품 결정이라 UX 개편마다
// 바뀌는데, manifest 에 넣으면 자산이 그대로인데도 Compiler 를 다시 돌려야 한다. 대신 코드의
// FAMILY 를 키로 써서 **자산 N행 표가 아니라 FAMILY 8행 표**만 든다.
import type { BoothAssetEntry } from './boothAssetManifest';
import type { CatalogItemVM } from './catalog';
import { findCatalogItem } from './catalog';

/** 자산의 본질적 분류 — assetCode 접두. 서비스가 커져도 이 축은 유지된다 */
export type AssetDomain = 'FURN' | 'STRUCT' | 'DISP' | 'DEVICE' | 'DECO' | 'BOOTH' | 'FACADE';

/** 사용자가 좌측에서 찾는 메뉴. 화면 소관이라 자유롭게 재편한다 */
export interface UiCategory {
  id: string;
  label: string;
}

/**
 * FAMILY → 메뉴. **이 표가 UI 개편의 유일한 수정 지점이다.**
 *
 * 자산이 늘어도 FAMILY 가 같으면 여기를 안 고쳐도 분류된다 —
 * `FURN_CHAIR_03_RED` 가 들어오면 그냥 "의자" 에 붙는다.
 */
const FAMILY_CATEGORY: Record<string, UiCategory> = {
  CHAIR: { id: 'chair', label: '의자' },
  TABLE: { id: 'table', label: '테이블' },
  COUNTER: { id: 'counter', label: '카운터' },
  DESK: { id: 'counter', label: '카운터' },
  PANEL: { id: 'panel', label: '패널' },
  BOARD: { id: 'panel', label: '패널' },
  TRUSS: { id: 'truss', label: '트러스' },
  BOX: { id: 'display', label: '전시장비' },
  STAND: { id: 'display', label: '전시장비' },
  SCREEN: { id: 'display', label: '전시장비' },
  KIOSK: { id: 'device', label: '전자기기' },
  TABLET: { id: 'device', label: '전자기기' },
  AGENT: { id: 'prop', label: '소품·장식' },
  SET: { id: 'prop', label: '소품·장식' },
};

/** 표에 없는 FAMILY 가 와도 사라지지 않는다 — 분류 실패를 조용한 누락으로 만들지 않는다 */
export const UNCATEGORIZED: UiCategory = { id: 'etc', label: '기타' };

/** 좌측 섹션 순서. 표에 없는 id 는 뒤로 밀린다 */
const CATEGORY_ORDER = ['chair', 'table', 'counter', 'panel', 'truss', 'display', 'device', 'prop', 'etc'];

export interface AssetCodeParts {
  domain: AssetDomain | null;
  family: string | null;
  variant: string | null;
}

/**
 * `<DOMAIN>_<FAMILY>[_<VARIANT>]` 을 가른다.
 *
 * 규칙에 안 맞는 코드(옛 목업·외부 유입)도 `null` 로 돌려주고 버리지 않는다.
 * 저장된 layout 에 옛 코드가 남아 있을 수 있어서다.
 */
export function parseAssetCode(assetCode: string): AssetCodeParts {
  const [head, family, ...rest] = assetCode.split('_');
  const domains: AssetDomain[] = ['FURN', 'STRUCT', 'DISP', 'DEVICE', 'DECO', 'BOOTH', 'FACADE'];
  const domain = domains.includes(head as AssetDomain) ? (head as AssetDomain) : null;
  return {
    domain,
    family: domain !== null && family !== undefined ? family : null,
    variant: rest.length > 0 ? rest.join('_') : null,
  };
}

/** assetCode 하나가 어느 메뉴에 걸리는가 */
export function categoryOf(assetCode: string): UiCategory {
  const { family } = parseAssetCode(assetCode);
  if (family === null) return UNCATEGORIZED;
  return FAMILY_CATEGORY[family] ?? UNCATEGORIZED;
}

export interface LibraryItem {
  assetCode: string;
  displayName: string;
  /** manifest 상대 경로. 없으면 UI 가 대체 표현을 쓴다 */
  thumbnail: string | null;
  objectType: BoothAssetEntry['objectType'];
  domain: AssetDomain | null;
  category: UiCategory;
  /** 미보유 = 잠금. 카탈로그에 그 코드가 없으면 잠그지 않는다 — 없는 것과 미보유는 다르다 */
  locked: boolean;
  /** 구매 가능하면 가격, 아니면 null */
  price: number | null;
}

export interface LibrarySection {
  category: UiCategory;
  items: LibraryItem[];
}

/** 코드에서 사람이 읽을 이름을 만든다 — manifest 에 표시명이 없을 때의 기본값 */
export function displayNameOf(assetCode: string): string {
  const { domain, family, variant } = parseAssetCode(assetCode);
  if (domain === null || family === null) return assetCode;
  const category = FAMILY_CATEGORY[family];
  const base = category === undefined ? family : category.label;
  return variant === null ? base : `${base} ${variant.replace(/_/g, ' ')}`;
}

/**
 * manifest + 카탈로그 → 좌측 섹션 목록.
 *
 * **GLB 는 여기서 건드리지 않는다.** 목록은 thumbnail 만 쓰고, 실제 모델은 사용자가 고른
 * 뒤에 URL 단위로 받는다(boothAssetCache). 목록을 보는 것만으로 전량 preload 하지 않는다.
 */
export function buildLibrary(assets: BoothAssetEntry[], catalog: CatalogItemVM[]): LibrarySection[] {
  const byCategory = new Map<string, LibrarySection>();

  for (const entry of assets) {
    const category = categoryOf(entry.assetCode);
    const cat = findCatalogItem(catalog, { code: entry.assetCode });
    const item: LibraryItem = {
      assetCode: entry.assetCode,
      displayName: displayNameOf(entry.assetCode),
      thumbnail: entry.thumbnail ?? null,
      objectType: entry.objectType,
      domain: parseAssetCode(entry.assetCode).domain,
      category,
      locked: cat?.locked ?? false,
      price: cat?.purchasable === true ? cat.price : null,
    };
    const section = byCategory.get(category.id);
    if (section === undefined) byCategory.set(category.id, { category, items: [item] });
    else section.items.push(item);
  }

  return [...byCategory.values()].sort((a, b) => {
    const ai = CATEGORY_ORDER.indexOf(a.category.id);
    const bi = CATEGORY_ORDER.indexOf(b.category.id);
    return (ai === -1 ? CATEGORY_ORDER.length : ai) - (bi === -1 ? CATEGORY_ORDER.length : bi);
  });
}

/** 검색 — 표시명과 코드 둘 다 본다. 코드로 찾는 사람이 실제로 있다 */
export function filterLibrary(sections: LibrarySection[], query: string): LibrarySection[] {
  const q = query.trim().toLowerCase();
  if (q === '') return sections;
  return sections
    .map((s) => ({
      category: s.category,
      items: s.items.filter(
        (i) => i.displayName.toLowerCase().includes(q) || i.assetCode.toLowerCase().includes(q),
      ),
    }))
    .filter((s) => s.items.length > 0);
}
