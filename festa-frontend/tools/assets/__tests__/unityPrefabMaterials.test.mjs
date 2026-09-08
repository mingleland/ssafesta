// MeshRenderer 재질 슬롯과 PrefabInstance 재질 override 파싱 (S15P21A604-527).
//
// 재질이 하나로 뭉개지던 결함이 여기서 시작했다 — MeshFilter(형상)만 읽고 MeshRenderer(재질)를
// 안 읽으면 조립체의 부위별 재질이 통째로 사라진다. `SurveyKiosk` 의 태블릿 화면이 카운터의
// 흰 플라스틱으로 덮여 흰 판이 됐다(LJH T-72).
//
// override 는 **`value:` 가 아니라 `objectReference:`** 에 값이 있다 — transform override 와
// 다른 자리다. 그리고 `target.fileID` 가 가리키는 renderer 의 그 슬롯 하나만 바꿔야 한다.
// 자식 트리 전체에 적용하면 형제 renderer 의 재질까지 덮어써 원본과 달라진다.
import { describe, expect, it } from 'vitest';
import { readInstanceMaterialOverrides, readMaterialSlots } from '../unity-prefab.mjs';

const BUILTIN = '0000000000000000f000000000000000';
const PLASTIC = '396abb32ad9012c4c923407a450e5fac';
const ALUMINIUM = 'a06b6ffaa65dcbe4a98df48d24d72c9d';

/** Counter01.prefab 의 실제 형태 — 내장 재질 2슬롯 renderer + 알루미늄 1슬롯 renderer */
const RENDERER_BUILTIN_2 = [
  'MeshRenderer:',
  '  m_Materials:',
  `  - {fileID: 10303, guid: ${BUILTIN}, type: 0}`,
  `  - {fileID: 10303, guid: ${BUILTIN}, type: 0}`,
  '  m_StaticBatchInfo:',
  '',
].join('\n');

const RENDERER_ALUMINIUM = [
  'MeshRenderer:',
  '  m_Materials:',
  `  - {fileID: 2100000, guid: ${ALUMINIUM}, type: 2}`,
  '  m_StaticBatchInfo:',
  '',
].join('\n');

/** SurveyKiosk.prefab 이 Counter01 인스턴스의 두 슬롯을 PlasticWhite 로 덮는 실제 형태 */
const OVERRIDES = [
  'PrefabInstance:',
  '  m_Modifications:',
  '  - target: {fileID: 4490367106449672, guid: d3d656f4611a5634481fa782337779c4, type: 3}',
  '    propertyPath: m_LocalEulerAnglesHint.z',
  '    value: 0',
  '    objectReference: {fileID: 0}',
  '  - target: {fileID: 23014050457739150, guid: d3d656f4611a5634481fa782337779c4, type: 3}',
  "    propertyPath: 'm_Materials.Array.data[0]'",
  '    value: ',
  `    objectReference: {fileID: 2100000, guid: ${PLASTIC}, type: 2}`,
  '  - target: {fileID: 23014050457739150, guid: d3d656f4611a5634481fa782337779c4, type: 3}',
  "    propertyPath: 'm_Materials.Array.data[1]'",
  '    value: ',
  `    objectReference: {fileID: 2100000, guid: ${PLASTIC}, type: 2}`,
  '  m_RemovedComponents: []',
  '',
].join('\n');

const crlf = (text) => text.replace(/\n/g, '\r\n');

describe('readMaterialSlots — 슬롯 순서가 submesh index 다', () => {
  it('guid 를 슬롯 순서대로 읽는다', () => {
    expect(readMaterialSlots(RENDERER_ALUMINIUM)).toEqual([{ fileID: '2100000', guid: ALUMINIUM }]);
  });

  it('Unity 내장 재질은 null 이다 — "재질 없음" 과 "guid 를 못 찾음" 은 다르다', () => {
    const slots = readMaterialSlots(RENDERER_BUILTIN_2);
    expect(slots).toHaveLength(2);
    expect(slots.every((s) => s.guid === null)).toBe(true);
  });

  it('CRLF 로 저장돼도 같은 값을 준다 — 이 자리에서 T-64·T-61 이 났다', () => {
    expect(readMaterialSlots(crlf(RENDERER_ALUMINIUM))).toEqual(readMaterialSlots(RENDERER_ALUMINIUM));
    expect(readMaterialSlots(crlf(RENDERER_BUILTIN_2))).toHaveLength(2);
  });

  it('MeshRenderer 가 아니면 빈 배열 — 없는 것을 지어내지 않는다', () => {
    expect(readMaterialSlots('MeshFilter:\n  m_Mesh: {fileID: 1}\n')).toEqual([]);
  });
});

describe('readInstanceMaterialOverrides — 값은 objectReference 에 있다', () => {
  it('target·슬롯·guid 를 그대로 읽는다', () => {
    expect(readInstanceMaterialOverrides(OVERRIDES)).toEqual([
      { targetFileID: '23014050457739150', slot: 0, guid: PLASTIC },
      { targetFileID: '23014050457739150', slot: 1, guid: PLASTIC },
    ]);
  });

  it('value: 만 읽으면 안 된다 — 재질 override 는 value 가 비어 있다', () => {
    // `value: ` 뒤에 아무것도 없다. 그래서 transform override 와 같은 방식으로는 못 읽는다
    expect(OVERRIDES).toContain("propertyPath: 'm_Materials.Array.data[0]'\n    value: \n");
    expect(readInstanceMaterialOverrides(OVERRIDES)[0].guid).toBe(PLASTIC);
  });

  it('transform override 를 재질 override 로 오인하지 않는다', () => {
    const targets = readInstanceMaterialOverrides(OVERRIDES).map((o) => o.targetFileID);
    expect(targets).not.toContain('4490367106449672');
  });

  it('override 는 지정된 renderer 하나만 가리킨다 — 형제 renderer 는 대상이 아니다', () => {
    // Counter01 의 AluminiumBrushed renderer(&23299921322947628)는 override 목록에 없다.
    // 이것이 자식 트리 전체 적용을 금지하는 이유다 — 적용하면 알루미늄이 흰 플라스틱이 된다
    const targets = new Set(readInstanceMaterialOverrides(OVERRIDES).map((o) => o.targetFileID));
    expect([...targets]).toEqual(['23014050457739150']);
  });

  it('CRLF 로 저장돼도 같은 값을 준다', () => {
    expect(readInstanceMaterialOverrides(crlf(OVERRIDES))).toEqual(readInstanceMaterialOverrides(OVERRIDES));
  });

  it('재질 override 가 없으면 빈 배열', () => {
    const onlyTransform = OVERRIDES.split('\n').slice(0, 6).join('\n');
    expect(readInstanceMaterialOverrides(onlyTransform)).toEqual([]);
  });
});
