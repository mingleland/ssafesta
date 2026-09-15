// 배치 범위·bounds 해석 회귀 (S15P21A604-785, GitLab #181).
//
// **절대 좌표를 단언하지 않는다.** 오브젝트마다 자기 AABB 가 있으므로 "x=4.6 이면 통과" 같은 문장은
// 성립하지 않는다. 허용 중심은 `부스 half extent − 회전 AABB half extent` 로 **계산해서** 경계를 본다.
//
// 높이도 배치 좌표가 아니다. `Position.y` 는 계약상 항상 0 이고(`types.ts`), 높이 판정은
// `world.max.y > booth.height` — 즉 **오브젝트 상단**이다. 중심 y 를 5.9 로 놓는 테스트는 틀린 테스트다.
import { describe, expect, it } from 'vitest';
import { isAreaOutOfBounds, rotateAABB, worldAABB } from '../../geometry.ts';
import { OBJECT_LOCAL_BOUNDS } from '../../objectTypes.ts';
import type { AABB } from '../../objectTypes.ts';
import { passageWarnings } from '../../passage.ts';
import type { LayoutObject } from '../../types.ts';
import { BOOTH_SIZE_FALLBACK } from '../../../../shared/config/studio.ts';
import { resolveLocalBounds } from '../../../../features/studio/model/useBoothAssets.ts';
import type { BoothAssetEntry } from '../../../../features/studio/model/boothAssetManifest.ts';

const BOOTH_6M = { width: 6, depth: 6, height: 2.72 } as const;

function obj(partial: Partial<LayoutObject> & Pick<LayoutObject, 'objectId' | 'type'>): LayoutObject {
  return { position: { x: 0, y: 0, z: 0 }, rotationY: 0, ...partial };
}

const asset = (over: Partial<BoothAssetEntry>): BoothAssetEntry => ({
  assetCode: 'DISP_BOX_01',
  objectType: 'DECORATION',
  typeDefault: true,
  url: 'x.glb',
  bytes: 1,
  triangles: 1,
  bounds: { min: [-0.3, 0, -0.3], max: [0.3, 1.6085, 0.3] },
  source: { fbx: 'x.FBX', unitScale: 0.0254, upAxis: 'zUp', rawBounds: { min: [0, 0, 0], max: [1, 1, 1] } },
  ...over,
});

/** 한 축의 허용 중심 구간 — 회전한 몸체가 벽을 넘지 않는 범위. 검증이 쓰는 정의와 같다 */
function allowedCenter(local: AABB, rotationY: number, half: number, axis: 'x' | 'z') {
  const r = rotateAABB(local, rotationY);
  return { lo: -half - r.min[axis], hi: half - r.max[axis] };
}

describe('resolveLocalBounds — 정본은 runtime manifest', () => {
  it('assetCode 가 맞으면 manifest 실측값을 쓴다', () => {
    const assets = [asset({ assetCode: 'STRUCT_PANEL_01', typeDefault: false, bounds: { min: [-1.5, 0, -0.1], max: [1.5, 2.4, 0.1] } })];
    const bounds = resolveLocalBounds({ type: 'DECORATION', assetCode: 'STRUCT_PANEL_01' }, assets);
    expect(bounds).toEqual({ min: { x: -1.5, y: 0, z: -0.1 }, max: { x: 1.5, y: 2.4, z: 0.1 } });
  });

  it('assetCode 가 없으면 그 타입의 typeDefault 자산으로 간다 — Unity 레지스트리와 같은 순서', () => {
    const assets = [asset({})];
    expect(resolveLocalBounds({ type: 'DECORATION' }, assets)?.max.y).toBeCloseTo(1.6085, 6);
  });

  it('manifest 에 없는 타입은 타입 기본 표로 떨어진다 — 기능형이 여기 해당한다', () => {
    const assets = [asset({})];
    expect(resolveLocalBounds({ type: 'AI_AGENT' }, assets)).toEqual(OBJECT_LOCAL_BOUNDS.AI_AGENT);
  });

  it('manifest 가 비면 전부 타입 기본 — 산출물 미생성이 기본 상태라 오늘과 같은 값이어야 한다', () => {
    for (const type of Object.keys(OBJECT_LOCAL_BOUNDS) as Array<keyof typeof OBJECT_LOCAL_BOUNDS>) {
      expect(resolveLocalBounds({ type }, [])).toEqual(OBJECT_LOCAL_BOUNDS[type]);
    }
  });
});

describe('footprint 9.4 × 6 × 5.9 — 허용 중심은 계산한다', () => {
  const BOOTH = BOOTH_SIZE_FALLBACK;
  const panel = OBJECT_LOCAL_BOUNDS.PROJECT_PANEL; // x 가 비대칭이다: min -0.78 / max 0.77

  it('폴백 치수가 BE 확정값이다', () => {
    expect(BOOTH).toEqual({ width: 9.4, depth: 6, height: 5.9 });
  });

  it.each([0, 45, 90, 180, 270])('회전 %i° 에서 x 경계 안은 통과, 밖은 거부', (rotationY) => {
    const { lo, hi } = allowedCenter(panel, rotationY, BOOTH.width / 2, 'x');
    expect(isAreaOutOfBounds(worldAABB(panel, rotationY, { x: hi, z: 0 }), BOOTH)).toBe(false);
    expect(isAreaOutOfBounds(worldAABB(panel, rotationY, { x: lo, z: 0 }), BOOTH)).toBe(false);
    expect(isAreaOutOfBounds(worldAABB(panel, rotationY, { x: hi + 1e-6, z: 0 }), BOOTH)).toBe(true);
    expect(isAreaOutOfBounds(worldAABB(panel, rotationY, { x: lo - 1e-6, z: 0 }), BOOTH)).toBe(true);
  });

  it('비대칭 AABB 가 축마다 따로 반영된다 — 단일 half-extent 로 줄이면 한쪽이 틀린다', () => {
    const { lo, hi } = allowedCenter(panel, 0, BOOTH.width / 2, 'x');
    expect(hi).toBeCloseTo(4.7 - 0.77, 9);
    expect(lo).toBeCloseTo(-4.7 + 0.78, 9);
    expect(hi).not.toBeCloseTo(-lo, 9);
  });

  it('같은 계산이 6m 부스에서는 좁아진다 — 규칙은 하나고 치수만 다르다', () => {
    const { lo, hi } = allowedCenter(panel, 0, BOOTH_6M.width / 2, 'x');
    expect(hi).toBeCloseTo(3 - 0.77, 9);
    expect(lo).toBeCloseTo(-3 + 0.78, 9);
    expect(isAreaOutOfBounds(worldAABB(panel, 0, { x: hi, z: 0 }), BOOTH_6M)).toBe(false);
    expect(isAreaOutOfBounds(worldAABB(panel, 0, { x: hi + 1e-6, z: 0 }), BOOTH_6M)).toBe(true);
  });

  it('z 는 6 유지라 깊이 판정이 그대로다', () => {
    const { hi } = allowedCenter(panel, 0, BOOTH.depth / 2, 'z');
    expect(hi).toBeCloseTo(3 - 0.18, 9);
  });
});

describe('높이 판정은 오브젝트 상단이지 배치 좌표가 아니다', () => {
  const BOOTH = BOOTH_SIZE_FALLBACK;

  it('PROJECT_PANEL(상단 2.72)은 2.72 부스에서도 5.9 부스에서도 통과한다', () => {
    const panel = OBJECT_LOCAL_BOUNDS.PROJECT_PANEL;
    expect(panel.max.y).toBeCloseTo(2.72, 9);
    expect(isAreaOutOfBounds(worldAABB(panel, 0, { x: 0, z: 0 }), BOOTH_6M)).toBe(false);
    expect(isAreaOutOfBounds(worldAABB(panel, 0, { x: 0, z: 0 }), BOOTH)).toBe(false);
  });

  it('상단 5.9 는 통과하고 5.91 은 거부된다 — 천장 램프가 5.94 부터라 5.9 가 실사용 상한이다', () => {
    const tall = { min: { x: -0.1, y: 0, z: -0.1 }, max: { x: 0.1, y: 5.9, z: 0.1 } };
    const tooTall = { min: { x: -0.1, y: 0, z: -0.1 }, max: { x: 0.1, y: 5.91, z: 0.1 } };
    expect(isAreaOutOfBounds(worldAABB(tall, 0, { x: 0, z: 0 }), BOOTH)).toBe(false);
    expect(isAreaOutOfBounds(worldAABB(tooTall, 0, { x: 0, z: 0 }), BOOTH)).toBe(true);
  });

  it('manifest 의 키 큰 자산도 같은 규칙을 탄다 — 해석기를 거쳐도 판정이 갈리지 않는다', () => {
    const assets = [asset({ assetCode: 'STRUCT_TRUSS_VERTICAL', typeDefault: false, bounds: { min: [-0.2, 0, -0.2], max: [0.2, 5.95, 0.2] } })];
    const local = resolveLocalBounds({ type: 'DECORATION', assetCode: 'STRUCT_TRUSS_VERTICAL' }, assets)!;
    expect(isAreaOutOfBounds(worldAABB(local, 0, { x: 0, z: 0 }), BOOTH)).toBe(true);
  });
});

describe('통행 격자가 footprint 를 따라간다', () => {
  // RECRUITMENT_BOARD 는 폭 3m 다. 두 개로 6m 를 막으면 6m 부스에서는 전폭이지만
  // 9.4m 부스에서는 양끝 1.7m 씩이 뚫려 있어 뒷공간이 고립되지 않는다.
  // 격자가 ±3 에 묶여 있으면 이 차이를 만들지 못한다.
  const wall = [
    obj({ objectId: 'wall-left', type: 'RECRUITMENT_BOARD', position: { x: -1.5, y: 0, z: -1 } }),
    obj({ objectId: 'wall-right', type: 'RECRUITMENT_BOARD', position: { x: 1.5, y: 0, z: -1 } }),
  ];

  it('6m 부스에서는 6m 벽이 전폭이라 뒷공간이 고립된다', () => {
    expect(passageWarnings(wall, BOOTH_6M).some((w) => w.rule === 'ISOLATED_AREA')).toBe(true);
  });

  it('9.4m 부스에서는 같은 벽이 양끝을 못 막아 고립되지 않는다', () => {
    expect(passageWarnings(wall, BOOTH_SIZE_FALLBACK).some((w) => w.rule === 'ISOLATED_AREA')).toBe(false);
  });

  it('9.4m 폭을 실제로 다 막으면 다시 고립된다 — 넓어진 만큼 더 필요할 뿐 규칙은 같다', () => {
    const wide = [
      ...wall,
      obj({ objectId: 'wall-far-left', type: 'RECRUITMENT_BOARD', position: { x: -3.2, y: 0, z: -1 } }),
      obj({ objectId: 'wall-far-right', type: 'RECRUITMENT_BOARD', position: { x: 3.2, y: 0, z: -1 } }),
    ];
    expect(passageWarnings(wide, BOOTH_SIZE_FALLBACK).some((w) => w.rule === 'ISOLATED_AREA')).toBe(true);
  });

  it('manifest 해석기를 넘겨도 빈 manifest 면 답이 같다 — 회귀 0', () => {
    const withResolver = passageWarnings(wall, BOOTH_6M, (o) => resolveLocalBounds(o, []));
    expect(withResolver).toEqual(passageWarnings(wall, BOOTH_6M));
  });
});

