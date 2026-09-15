// §10-3 통행 판정 회귀 방어. FRONT_BLOCKED 케이스는 브라우저 실측(quickstart §6b·§6, 2026-08-22)
// 에서 FE·mock 서버가 동일하게 경고한 배치를 그대로 가져왔다.
import { describe, expect, it } from 'vitest';
import { passageWarnings } from '../../passage.ts';
import type { LayoutObject } from '../../types.ts';

// 격자가 footprint 에서 유도되도록 바뀌었다 (S15P21A604-785). 이 파일의 기대값은 전부 6m 정사각형
// 기준의 실측이므로 **6×6 을 명시해 그대로 잠근다** — 같은 입력이 예전 120×120 과 같은 답을 내는지가
// 이번 변경의 회귀 기준이다. 넓어진 footprint 판정은 assetBounds.test.ts 가 따로 본다.
const BOOTH_6M = { width: 6, depth: 6 } as const;

function obj(partial: Partial<LayoutObject> & Pick<LayoutObject, 'objectId' | 'type'>): LayoutObject {
  return { position: { x: 0, y: 0, z: 0 }, rotationY: 0, ...partial };
}

describe('passageWarnings — 빈 배치·안전한 배치', () => {
  it('빈 배치는 경고가 없다', () => {
    expect(passageWarnings([], BOOTH_6M)).toEqual([]);
  });

  it('부스 중앙에 하나만 정면이 열려 있으면 경고가 없다', () => {
    const warnings = passageWarnings([obj({ objectId: 'a', type: 'AI_AGENT' })], BOOTH_6M);
    expect(warnings).toEqual([]);
  });
});

describe('passageWarnings — FRONT_BLOCKED (실측: SURVEY_KIOSK가 AI_AGENT 관람 띠를 막음)', () => {
  it('상호작용 파츠 바로 앞을 다른 오브젝트가 가리면 경고한다', () => {
    const objects = [
      obj({ objectId: 'agent', type: 'AI_AGENT', position: { x: 0, y: 0, z: 0 } }),
      obj({ objectId: 'kiosk', type: 'SURVEY_KIOSK', position: { x: 0, y: 0, z: 0.5 } }),
    ];
    const warnings = passageWarnings(objects, BOOTH_6M);
    expect(warnings).toContainEqual({
      rule: 'FRONT_BLOCKED',
      objectId: 'agent',
      message: '관람 띠 도달 가능 비율이 50% 미만입니다.',
    });
  });

  it('장식(FURNITURE·DECORATION)은 관람 띠 자체가 없어 막혀도 경고하지 않는다', () => {
    const objects = [
      obj({ objectId: 'deco', type: 'DECORATION', position: { x: 0, y: 0, z: 0 } }),
      obj({ objectId: 'blocker', type: 'FURNITURE', position: { x: 0, y: 0, z: 0.5 } }),
    ];
    expect(passageWarnings(objects, BOOTH_6M).some((w) => w.objectId === 'deco')).toBe(false);
  });
});

describe('passageWarnings — ISOLATED_AREA (전체 너비를 막아 뒷공간을 고립시킨다)', () => {
  it('부스 전체 폭을 벽으로 막으면 뒷공간이 1㎡ 이상 고립된 것으로 경고한다', () => {
    // RECRUITMENT_BOARD(폭 3m) 2개를 이어붙여 6m 폭 전체를 z=-1 부근에서 막는다 —
    // 그 뒤(z<~-1.2)는 정면(+z) 개방 지점에서 flood fill로 닿지 않는다.
    const objects = [
      obj({ objectId: 'wall-left', type: 'RECRUITMENT_BOARD', position: { x: -1.5, y: 0, z: -1 } }),
      obj({ objectId: 'wall-right', type: 'RECRUITMENT_BOARD', position: { x: 1.5, y: 0, z: -1 } }),
    ];
    const warnings = passageWarnings(objects, BOOTH_6M);
    expect(warnings.some((w) => w.rule === 'ISOLATED_AREA')).toBe(true);
  });

  it('막힌 벽에 사람이 지나갈 틈을 남기면 고립되지 않는다', () => {
    // 벽 두 조각을 양쪽 끝에만 붙여 중앙(x -1.5..1.5)을 통로로 남긴다.
    const objects = [
      obj({ objectId: 'wall-left', type: 'RECRUITMENT_BOARD', position: { x: -1.5 - 1.5 - 1.5, y: 0, z: -1 } }),
      obj({ objectId: 'wall-right', type: 'RECRUITMENT_BOARD', position: { x: 1.5 + 1.5 + 1.5, y: 0, z: -1 } }),
    ];
    // 두 벽이 부스 밖으로 밀려나 사실상 아무것도 막지 않는 통제군 — ISOLATED_AREA가 없어야 한다.
    const warnings = passageWarnings(objects, BOOTH_6M);
    expect(warnings.some((w) => w.rule === 'ISOLATED_AREA')).toBe(false);
  });
});
