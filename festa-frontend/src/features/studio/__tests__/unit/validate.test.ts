// 사전 경고·오류 판정 회귀 방어. AREA_OUT_OF_BOUNDS 케이스는 브라우저 실측(quickstart §6b,
// 2026-08-22)에서 FE·mock 서버가 동일하게 거부한 배치를 그대로 가져왔다.
import { describe, expect, it } from 'vitest';
import type { LayoutObject } from '../../../../entities/layout/types.ts';
import { BOOTH_SIZE_FALLBACK } from '../../../../shared/config/studio.ts';
import { precheckErrors, precheckWarnings } from '../../lib/validate.ts';

function obj(partial: Partial<LayoutObject> & Pick<LayoutObject, 'objectId' | 'type'>): LayoutObject {
  return { position: { x: 0, y: 0, z: 0 }, rotationY: 0, ...partial };
}

describe('precheckWarnings — CONFIG_NOT_LINKED', () => {
  it('연결 요건이 있는 기능형(warnOnMissingConfig)이 configId 없으면 경고한다', () => {
    const warnings = precheckWarnings([obj({ objectId: 'a', type: 'AI_AGENT' })]);
    expect(warnings).toEqual([{ rule: 'CONFIG_NOT_LINKED', objectId: 'a', message: '연결된 콘텐츠가 없습니다.' }]);
  });

  it('configId가 있으면 경고하지 않는다', () => {
    expect(precheckWarnings([obj({ objectId: 'a', type: 'AI_AGENT', configId: 1 })])).toEqual([]);
  });

  it('장식형(FURNITURE·DECORATION)은 연결 요건이 없어 경고하지 않는다', () => {
    expect(precheckWarnings([obj({ objectId: 'a', type: 'FURNITURE' })])).toEqual([]);
  });

  it('미지 타입은 조용히 건너뛴다 (SC-005, T026) — 판정은 서버 몫', () => {
    // @ts-expect-error 서버가 신설했지만 로컬 표에 아직 없는 타입을 시뮬레이션한다
    expect(precheckWarnings([obj({ objectId: 'a', type: 'GAME_PORTAL' })])).toEqual([]);
  });
});

describe('precheckErrors — OBJECT_LIMIT · DUPLICATE_OBJECT_ID', () => {
  it('상한 이하면 OBJECT_LIMIT이 없다', () => {
    const objects = [obj({ objectId: 'a', type: 'FURNITURE' })];
    expect(precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK)).toEqual([]);
  });

  it('상한 초과면 OBJECT_LIMIT 1건', () => {
    const objects = Array.from({ length: 3 }, (_, i) => obj({ objectId: `o${i}`, type: 'FURNITURE' }));
    const errors = precheckErrors(objects, 2, BOOTH_SIZE_FALLBACK);
    expect(errors).toContainEqual({ rule: 'OBJECT_LIMIT', message: '오브젝트는 2개까지입니다. (현재 3개)' });
  });

  it('objectId 중복이면 DUPLICATE_OBJECT_ID', () => {
    const objects = [obj({ objectId: 'dup', type: 'FURNITURE' }), obj({ objectId: 'dup', type: 'DECORATION' })];
    const errors = precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK);
    expect(errors).toContainEqual({ rule: 'DUPLICATE_OBJECT_ID', objectId: 'dup', message: '오브젝트 식별자가 중복됐습니다.' });
  });
});

describe('precheckErrors — AREA_OUT_OF_BOUNDS (§10-1·§10-2)', () => {
  it('앵커가 안이어도 회전한 실물이 부스 밖으로 나가면 거부한다 (실측: VIDEO_SCREEN x=2.9, rotationY=90)', () => {
    const objects = [obj({ objectId: 'screen', type: 'VIDEO_SCREEN', position: { x: 2.9, y: 0, z: 0 }, rotationY: 90 })];
    const errors = precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK);
    expect(errors).toContainEqual({
      rule: 'AREA_OUT_OF_BOUNDS',
      objectId: 'screen',
      message: '회전한 실물이 부스 영역을 벗어났습니다.',
    });
  });

  it('같은 회전이라도 부스 중앙 쪽으로 당기면 통과한다 (앵커 위치가 판정을 뒤집는다)', () => {
    const objects = [obj({ objectId: 'screen', type: 'VIDEO_SCREEN', position: { x: 0, y: 0, z: 0 }, rotationY: 90 })];
    const errors = precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK);
    expect(errors.some((e) => e.rule === 'AREA_OUT_OF_BOUNDS')).toBe(false);
  });

  it('부스 정중앙은 어떤 회전이든 안전하다', () => {
    for (const rotationY of [0, 45, 90, 180, 270]) {
      const objects = [obj({ objectId: 'x', type: 'RECRUITMENT_BOARD', position: { x: 0, y: 0, z: 0 }, rotationY })];
      expect(precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK)).toEqual([]);
    }
  });

  it('미지 타입은 실물 크기를 몰라 판정하지 않는다 (SC-005)', () => {
    const objects = [
      // @ts-expect-error 서버 신설 타입 시뮬레이션
      obj({ objectId: 'x', type: 'GAME_PORTAL', position: { x: 100, y: 0, z: 100 } }),
    ];
    expect(precheckErrors(objects, 12, BOOTH_SIZE_FALLBACK)).toEqual([]);
  });
});
