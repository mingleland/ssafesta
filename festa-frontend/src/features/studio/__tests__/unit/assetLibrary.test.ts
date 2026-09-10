// Asset Library 모델 — 좌측 패널이 manifest 를 어떻게 읽는가 (S15P21A604-551).
//
// 여기서 잠그는 것은 **분류 규칙**이다. 예전 팔레트는 자산 표를 손으로 들었고 그 코드 7종이
// 저장소 어디에도 없어 실물과 교집합 0 이었다. 그 구조로 돌아가지 않게, "코드에서 메뉴가
// 나온다" 는 성질과 "모르는 코드도 사라지지 않는다" 는 성질을 고정한다.
import { describe, expect, it } from 'vitest';
import {
  UNCATEGORIZED,
  buildLibrary,
  categoryOf,
  displayNameOf,
  filterLibrary,
  parseAssetCode,
} from '../../model/assetLibrary';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import type { CatalogItemVM } from '../../model/catalog';

const entry = (assetCode: string, over: Partial<BoothAssetEntry> = {}): BoothAssetEntry => ({
  assetCode,
  objectType: 'FURNITURE',
  typeDefault: false,
  url: `${assetCode}.glb`,
  thumbnail: `${assetCode}.webp`,
  bytes: 1000,
  triangles: 100,
  bounds: { min: [0, 0, 0], max: [1, 1, 1] },
  source: { fbx: 'x.fbx', unitScale: 1, upAxis: 'yUp', rawBounds: { min: [0, 0, 0], max: [1, 1, 1] } },
  ...over,
});

const catalogItem = (code: string, over: Partial<CatalogItemVM> = {}): CatalogItemVM => ({
  itemId: 1,
  code,
  name: code,
  equipSlot: '',
  assetKey: code,
  price: 100,
  owned: false,
  locked: false,
  purchasable: false,
  ...over,
});

describe('parseAssetCode — DOMAIN_FAMILY_VARIANT', () => {
  it('세 조각으로 가른다', () => {
    expect(parseAssetCode('FURN_CHAIR_01_BLUE')).toEqual({ domain: 'FURN', family: 'CHAIR', variant: '01_BLUE' });
  });

  it('VARIANT 가 없어도 된다', () => {
    expect(parseAssetCode('STRUCT_TRUSS')).toEqual({ domain: 'STRUCT', family: 'TRUSS', variant: null });
  });

  it('규칙 밖 코드도 버리지 않는다 — 저장된 layout 에 옛 코드가 남아 있다', () => {
    expect(parseAssetCode('COUNTER_GRAPHIC').domain).toBeNull();
    expect(parseAssetCode('COUNTER_GRAPHIC').family).toBeNull();
  });
});

describe('categoryOf — FAMILY 가 메뉴를 정한다', () => {
  it('같은 FAMILY 는 VARIANT 가 달라도 한 메뉴다', () => {
    expect(categoryOf('FURN_CHAIR_01_BLUE').id).toBe('chair');
    expect(categoryOf('FURN_CHAIR_02_WHITE').id).toBe('chair');
  });

  it('domain 이 달라도 FAMILY 가 같으면 같은 메뉴다 — 메뉴는 domain 이 아니라 FAMILY 소관이다', () => {
    expect(categoryOf('FURN_COUNTER_01').id).toBe('counter');
    expect(categoryOf('BOOTH_DESK_CONSULT').id).toBe('counter');
  });

  it('표에 없는 FAMILY 는 기타로 간다 — 조용히 사라지지 않는다', () => {
    expect(categoryOf('FURN_UNKNOWNFAMILY_01')).toEqual(UNCATEGORIZED);
  });

  it('규칙 밖 코드도 기타로 살아남는다', () => {
    expect(categoryOf('PLANT')).toEqual(UNCATEGORIZED);
  });
});

describe('displayNameOf', () => {
  it('메뉴 라벨 + VARIANT 로 읽을 이름을 만든다', () => {
    expect(displayNameOf('FURN_CHAIR_01_BLUE')).toBe('의자 01 BLUE');
  });

  it('규칙 밖 코드는 코드 그대로 보여 준다 — 지어내지 않는다', () => {
    expect(displayNameOf('PLANT')).toBe('PLANT');
  });
});

describe('buildLibrary', () => {
  it('메뉴별로 묶고 정해진 순서로 낸다', () => {
    const sections = buildLibrary(
      [entry('STRUCT_TRUSS_BASE'), entry('FURN_CHAIR_01_WHITE'), entry('FURN_TABLE_ROUND')],
      [],
    );
    expect(sections.map((s) => s.category.id)).toEqual(['chair', 'table', 'truss']);
  });

  it('manifest 의 thumbnail 을 그대로 쓴다 — CSS 목업이 아니다', () => {
    const [section] = buildLibrary([entry('FURN_CHAIR_01_WHITE')], []);
    expect(section.items[0].thumbnail).toBe('FURN_CHAIR_01_WHITE.webp');
  });

  it('thumbnail 이 없으면 null 이다 — 없는 것을 있는 척하지 않는다', () => {
    const [section] = buildLibrary([entry('FURN_CHAIR_01_WHITE', { thumbnail: null })], []);
    expect(section.items[0].thumbnail).toBeNull();
  });

  it('카탈로그에 없는 코드는 잠그지 않는다 — "카탈로그에 없음" 과 "미보유" 는 다르다', () => {
    const [section] = buildLibrary([entry('FURN_CHAIR_01_WHITE')], []);
    expect(section.items[0].locked).toBe(false);
    expect(section.items[0].price).toBeNull();
  });

  it('카탈로그가 잠갔으면 잠근다', () => {
    const [section] = buildLibrary(
      [entry('FURN_CHAIR_01_WHITE')],
      [catalogItem('FURN_CHAIR_01_WHITE', { locked: true, purchasable: true, price: 250 })],
    );
    expect(section.items[0].locked).toBe(true);
    expect(section.items[0].price).toBe(250);
  });

  it('manifest 가 비면 섹션도 없다 — manifest 없음이 기본 상태다', () => {
    expect(buildLibrary([], [])).toEqual([]);
  });

  it('objectType 을 그대로 옮긴다 — 계약 축은 건드리지 않는다', () => {
    const [section] = buildLibrary([entry('BOOTH_KIOSK_SURVEY', { objectType: 'SURVEY_KIOSK' })], []);
    expect(section.items[0].objectType).toBe('SURVEY_KIOSK');
  });
});

describe('filterLibrary', () => {
  const sections = buildLibrary([entry('FURN_CHAIR_01_WHITE'), entry('STRUCT_TRUSS_BASE')], []);

  it('빈 질의는 그대로 돌려준다', () => {
    expect(filterLibrary(sections, '   ')).toEqual(sections);
  });

  it('표시명으로 찾는다', () => {
    const found = filterLibrary(sections, '의자');
    expect(found).toHaveLength(1);
    expect(found[0].items[0].assetCode).toBe('FURN_CHAIR_01_WHITE');
  });

  it('코드로도 찾는다 — 코드로 찾는 사람이 실제로 있다', () => {
    expect(filterLibrary(sections, 'truss')[0].items[0].assetCode).toBe('STRUCT_TRUSS_BASE');
  });

  it('결과가 없는 섹션은 빼고 준다', () => {
    expect(filterLibrary(sections, '없는것')).toEqual([]);
  });
});
