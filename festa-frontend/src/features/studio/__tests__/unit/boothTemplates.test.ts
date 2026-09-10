// Booth Template — 초기 배치 recipe (S15P21A604-551).
//
// 잠그는 성질은 둘이다.
//   ① Template 적용 결과가 **일반 LayoutObject 와 구분되지 않는다** — 전용 포맷을 만들지 않는다
//   ② 템플릿 구성이 **실제 자산 코드**다 — 없는 자산으로 템플릿을 채우지 않는다
import { describe, expect, it } from 'vitest';
import { BOOTH_TEMPLATES, findTemplate, instantiateTemplate } from '../../model/boothTemplates';
import { parseAssetCode } from '../../model/assetLibrary';
import { OBJECT_TYPES } from '../../../../entities/layout/objectTypes';

describe('BOOTH_TEMPLATES — 구성이 실제 자산이다', () => {
  it('빈 부스가 있다 — 템플릿을 강요하지 않는다', () => {
    const empty = findTemplate('EMPTY');
    expect(empty).toBeDefined();
    expect(empty?.objects).toEqual([]);
  });

  it('templateCode 가 유일하다', () => {
    const codes = BOOTH_TEMPLATES.map((t) => t.templateCode);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('모든 오브젝트가 canonical assetCode 규칙을 따른다', () => {
    for (const t of BOOTH_TEMPLATES) {
      for (const o of t.objects) {
        expect(parseAssetCode(o.assetCode).domain, `${t.templateCode}/${o.assetCode}`).not.toBeNull();
      }
    }
  });

  it('모든 objectType 이 계약 10종 안에 있다', () => {
    for (const t of BOOTH_TEMPLATES) {
      for (const o of t.objects) {
        expect(OBJECT_TYPES, `${t.templateCode}/${o.assetCode}`).toContain(o.objectType);
      }
    }
  });

  it('배치가 6×6 부스 안이다 — 원점은 바닥 중앙이다', () => {
    for (const t of BOOTH_TEMPLATES) {
      for (const o of t.objects) {
        expect(Math.abs(o.x), `${t.templateCode} x`).toBeLessThanOrEqual(3);
        expect(Math.abs(o.z), `${t.templateCode} z`).toBeLessThanOrEqual(3);
      }
    }
  });

  it('회전이 [0,360) 이다 — 계약 범위', () => {
    for (const t of BOOTH_TEMPLATES) {
      for (const o of t.objects) {
        expect(o.rotationY).toBeGreaterThanOrEqual(0);
        expect(o.rotationY).toBeLessThan(360);
      }
    }
  });

  it('썸네일 키는 그 템플릿이 실제로 쓰는 자산이다 — 별도 이미지를 만들지 않는다', () => {
    for (const t of BOOTH_TEMPLATES) {
      if (t.thumbnailAssetCode === null) continue;
      expect(t.objects.map((o) => o.assetCode), t.templateCode).toContain(t.thumbnailAssetCode);
    }
  });
});

describe('instantiateTemplate — 결과가 일반 LayoutObject 와 같다', () => {
  const template = findTemplate('CONSULT')!;

  it('오브젝트 수가 그대로다', () => {
    let n = 0;
    const objects = instantiateTemplate(template, () => `id-${n++}`);
    expect(objects).toHaveLength(template.objects.length);
  });

  it('계약 필드만 낸다 — y 는 항상 0 이다', () => {
    let n = 0;
    for (const o of instantiateTemplate(template, () => `id-${n++}`)) {
      expect(o.position.y).toBe(0);
      expect(Object.keys(o).sort()).toEqual(['assetCode', 'objectId', 'position', 'rotationY', 'type'].sort());
    }
  });

  it('objectId 가 서로 다르다', () => {
    let n = 0;
    const ids = instantiateTemplate(template, () => `id-${n++}`).map((o) => o.objectId);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('assetCode·위치·회전을 그대로 옮긴다', () => {
    let n = 0;
    const [first] = instantiateTemplate(template, () => `id-${n++}`);
    const source = template.objects[0];
    expect(first.assetCode).toBe(source.assetCode);
    expect(first.position.x).toBe(source.x);
    expect(first.position.z).toBe(source.z);
    expect(first.rotationY).toBe(source.rotationY);
  });

  it('빈 부스는 아무것도 만들지 않는다', () => {
    expect(instantiateTemplate(findTemplate('EMPTY')!)).toEqual([]);
  });
});
