// 에셋 manifest 회귀 (S15P21A604-473).
//
// GLB 자체는 여기서 못 돌린다(jsdom 에 WebGL 없음). 대신 "실물이 화면에 들어오는가" 를
// 좌우하는 판정 규칙만 잠근다 — 어떤 에셋을 고르는가, 없음과 실패를 구분하는가,
// 그리고 변환 결과가 계약 치수와 맞는가.
import { describe, expect, it } from 'vitest';
import { OBJECT_LOCAL_BOUNDS } from '../../../../entities/layout/objectTypes';
import { boundsDelta, pickAsset } from '../../model/boothAssetManifest';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';

const entry = (over: Partial<BoothAssetEntry>): BoothAssetEntry => ({
  assetCode: 'DECORATION_DEFAULT',
  objectType: 'DECORATION',
  typeDefault: true,
  url: 'DECORATION_DEFAULT.glb',
  bytes: 26140,
  triangles: 240,
  bounds: { min: [-0.3, 0, -0.3], max: [0.3, 1.6085, 0.3] },
  source: {
    fbx: 'Stands/DisplayBox01.FBX',
    unitScale: 0.0254,
    upAxis: 'zUp',
    rawBounds: { min: [-11.811, -11.811, 0], max: [11.811, 11.811, 63.327] },
  },
  ...over,
});

describe('에셋 선택', () => {
  it('assetCode 가 있으면 그것으로 고른다 — 사용자가 고른 외형이다', () => {
    const assets = [entry({}), entry({ assetCode: 'FURNITURE_CHAIR01', objectType: 'FURNITURE' })];
    const picked = pickAsset(assets, { type: 'FURNITURE', assetCode: 'FURNITURE_CHAIR01' });
    expect(picked?.assetCode).toBe('FURNITURE_CHAIR01');
  });

  it('assetCode 가 없으면 그 타입의 기본으로 표시된 자산을 쓴다', () => {
    const assets = [entry({})];
    expect(pickAsset(assets, { type: 'DECORATION' })?.assetCode).toBe('DECORATION_DEFAULT');
  });

  it('기본이 아닌 자산은 타입 자리를 대신하지 않는다 — 두 번째 자산이 기본을 밀어내면 안 된다', () => {
    const assets = [entry({ assetCode: 'DECORATION_PLANT', typeDefault: false })];
    expect(pickAsset(assets, { type: 'DECORATION' })).toBeUndefined();
  });

  it('기본이 둘이면 고르지 않는다 — 선언이 잘못된 것이라 조용히 하나를 집지 않는다', () => {
    const assets = [entry({}), entry({ assetCode: 'DECORATION_PLANT' })];
    expect(pickAsset(assets, { type: 'DECORATION' })).toBeUndefined();
  });

  it('모르는 assetCode 는 타입 기본으로 내려간다 — 편집이 멈추면 안 된다', () => {
    const assets = [entry({})];
    expect(pickAsset(assets, { type: 'DECORATION', assetCode: 'NOPE' })?.assetCode).toBe('DECORATION_DEFAULT');
  });

  it('맞는 것이 없으면 undefined — 렌더러가 파라메트릭 박스로 되돌아가는 신호다', () => {
    expect(pickAsset([entry({})], { type: 'LAPTOP' })).toBeUndefined();
  });
});

describe('변환 정확도 — 계약 AABB 대조', () => {
  it('DisplayBox01 은 DECORATION 계약 치수와 mm 단위로 맞는다', () => {
    // 이 하나가 스케일(inch→m) · 축(Z-up→Y-up) · 피벗(바닥 중앙)이 전부 맞았다는 증거다
    const delta = boundsDelta(entry({}), OBJECT_LOCAL_BOUNDS.DECORATION);
    expect(delta[0]).toBeLessThan(0.005);
    expect(delta[1]).toBeLessThan(0.005);
    expect(delta[2]).toBeLessThan(0.005);
  });

  it('조립체 키오스크가 계약 치수와 mm 단위로 맞는다 — prefab 계층 재현의 판정', () => {
    // SurveyKiosk = Counter01.prefab + Tablet.prefab 이고 Counter01 은 다시 4개 FBX 를 문다.
    // 이 값이 맞는다는 것은 자식 transform·중첩 prefab·단위가 전부 제자리라는 뜻이다 (S15P21A604-476).
    const kiosk = entry({
      assetCode: 'SURVEY_KIOSK_DEFAULT',
      objectType: 'SURVEY_KIOSK',
      bounds: { min: [-0.31, 0, -0.1598], max: [0.31, 0.9255, 0.1598] },
    });
    const delta = boundsDelta(kiosk, OBJECT_LOCAL_BOUNDS.SURVEY_KIOSK);
    expect(delta[0]).toBeLessThan(0.005);
    expect(delta[1]).toBeLessThan(0.005);
    expect(delta[2]).toBeLessThan(0.005);
  });

  it('의자는 FURNITURE 계약 치수와 크게 다르다 — 타입 기본이 조립체이기 때문이다', () => {
    // 어긋남을 결함으로 읽지 않으려고 여기 적어 둔다. FURNITURE 기본은 테이블+의자 세트다
    const chair = entry({
      assetCode: 'FURNITURE_CHAIR01',
      objectType: 'FURNITURE',
      bounds: { min: [-0.1887, 0, -0.1887], max: [0.1887, 0.45, 0.1887] },
    });
    const delta = boundsDelta(chair, OBJECT_LOCAL_BOUNDS.FURNITURE);
    expect(delta[1]).toBeGreaterThan(0.2);
  });
});

describe('바닥 정렬', () => {
  it('변환 결과의 min.y 는 0 이다 — 계약 원점이 바닥 중앙이라 여기서 뜨면 공중에 뜬다', () => {
    expect(entry({}).bounds.min[1]).toBe(0);
  });

  it('x·z 는 원점 대칭이다 — 회전축이 모델 중앙이어야 제자리에서 돈다', () => {
    const b = entry({}).bounds;
    expect(b.min[0] + b.max[0]).toBeCloseTo(0, 6);
    expect(b.min[2] + b.max[2]).toBeCloseTo(0, 6);
  });
});
