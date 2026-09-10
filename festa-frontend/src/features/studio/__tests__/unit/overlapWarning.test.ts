// 배치 겹침 표시 (S15P21A604-607).
//
// 두 경계를 고정한다.
//   ① **맞닿은 것은 겹친 것이 아니다** — 스냅 격자에 딱 붙여 놓은 배치가 경고로 뜨면 못 쓴다
//   ② **게시를 막지 않는다** — 공개 판정의 권위는 서버이고(헌법 16조) 서버가 겹침을 어떻게
//      다루는지 아직 확정되지 않았다. FE 가 더 엄격해지면 서버가 허용하는 배치를 FE 가 막는다
import { describe, expect, it } from 'vitest';
import { precheckErrors, precheckWarnings } from '../../lib/validate';
import { overlappingObjectIds } from '../../lib/overlap';
import { OBJECT_LOCAL_BOUNDS } from '../../../../entities/layout/objectTypes';
import type { LayoutObject } from '../../../../entities/layout/types';

const BOUNDS = { width: 6, depth: 6, height: 2.72 };

// LAPTOP 은 x·z 가 ±0.4 인 정사각에 가까워 간격 계산이 읽기 쉽다
const HALF = OBJECT_LOCAL_BOUNDS.LAPTOP.max.x; // 0.4

function laptop(objectId: string, x: number, z = 0): LayoutObject {
  return { objectId, type: 'LAPTOP', position: { x, y: 0, z }, rotationY: 0 };
}

describe('겹침 판정', () => {
  it('같은 자리에 겹쳐 놓으면 둘 다 잡힌다', () => {
    const hit = overlappingObjectIds([laptop('a', 0), laptop('b', 0.1)]);
    expect([...hit].sort()).toEqual(['a', 'b']);
  });

  it('맞닿기만 하면 겹친 것이 아니다', () => {
    // 폭이 각각 2*HALF 이므로 중심 간격이 정확히 2*HALF 이면 변이 맞닿는다
    const hit = overlappingObjectIds([laptop('a', 0), laptop('b', HALF * 2)]);
    expect(hit.size).toBe(0);
  });

  it('떨어져 있으면 잡히지 않는다', () => {
    const hit = overlappingObjectIds([laptop('a', 0), laptop('b', 3)]);
    expect(hit.size).toBe(0);
  });

  it('z 축으로 겹쳐도 잡힌다 — x 만 보면 놓친다', () => {
    const hit = overlappingObjectIds([laptop('a', 0, 0), laptop('b', 0, 0.2)]);
    expect(hit.size).toBe(2);
  });

  it('세 개가 한 자리에 있으면 셋 다 잡힌다', () => {
    const hit = overlappingObjectIds([laptop('a', 0), laptop('b', 0.1), laptop('c', 0.2)]);
    expect(hit.size).toBe(3);
  });

  it('미지 타입은 건너뛴다 — 실물 크기를 모르면 겹침도 판정할 수 없다', () => {
    const unknown = { objectId: 'x', type: 'FUTURE_TYPE', position: { x: 0, y: 0, z: 0 }, rotationY: 0 } as unknown as LayoutObject;
    const hit = overlappingObjectIds([laptop('a', 0), unknown]);
    expect(hit.size).toBe(0);
  });

  it('회전을 반영한다 — 회전한 몸체가 옆으로 걸치면 잡힌다', () => {
    const wide: LayoutObject = { objectId: 'w', type: 'VIDEO_SCREEN', position: { x: 0, y: 0, z: 0 }, rotationY: 0 };
    const beside: LayoutObject = { objectId: 'n', type: 'VIDEO_SCREEN', position: { x: 0, y: 0, z: 1 }, rotationY: 0 };
    // 회전 전에는 z 로 1m 떨어져 안 겹친다(깊이 ±0.15)
    expect(overlappingObjectIds([wide, beside]).size).toBe(0);
    // 90° 돌리면 폭(2.7m)이 z 축으로 눕는다
    expect(overlappingObjectIds([{ ...wide, rotationY: 90 }, { ...beside, rotationY: 90 }]).size).toBe(2);
  });
});

describe('겹침은 경고이지 게시 차단이 아니다', () => {
  const overlapped = [laptop('a', 0), laptop('b', 0.1)];

  it('사전 경고에 뜬다', () => {
    const warnings = precheckWarnings(overlapped);
    const overlap = warnings.filter((w) => w.rule === 'OBJECTS_OVERLAP');
    expect(overlap.length).toBe(2);
    expect(overlap[0].message).toContain('겹');
  });

  it('사전 오류에는 없다 — 서버가 허용하는 배치를 FE 가 막지 않는다', () => {
    const errors = precheckErrors(overlapped, 12, BOUNDS);
    expect(errors.some((e) => e.rule === 'OBJECTS_OVERLAP')).toBe(false);
    expect(errors).toEqual([]);
  });

  it('겹치지 않으면 경고도 없다', () => {
    const warnings = precheckWarnings([laptop('a', 0), laptop('b', 3)]);
    expect(warnings.some((w) => w.rule === 'OBJECTS_OVERLAP')).toBe(false);
  });
});
