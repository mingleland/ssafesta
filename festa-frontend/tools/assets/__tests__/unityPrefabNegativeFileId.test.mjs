// Unity `.prefab` 의 **음수 fileID** 를 읽는지 (S15P21A604-552).
//
// FBX 안 서브에셋(메시·재질)을 가리키는 fileID 는 Unity 내부 해시라 **자주 음수**다.
// 정규식이 `\d+` 만 받으면 그 참조가 통째로 안 보이고, 오류도 경고도 없이 메시가 빠진다.
//
// 실측으로 드러났다 — CarnivalKit 42 prefab 의 `m_Mesh` 288 건 중 **136 건이 음수**였고,
// 팝콘 카트(`PF_Popcorn_Cart`)는 MeshFilter 5 개가 전부 음수라 **0 tri 로 로드**됐다.
// 조용한 실패라 tri 합계만 보면 "가벼운 자산" 으로 보인다.
import { describe, expect, it } from 'vitest';
import { readInstanceMaterialOverrides, readMaterialSlots } from '../unity-prefab.mjs';

describe('readMaterialSlots — 음수 fileID', () => {
  it('음수 슬롯을 읽는다', () => {
    const body = [
      'MeshRenderer:',
      '  m_Materials:',
      '  - {fileID: -6737146398385819516, guid: 1f281ad732a595143bb574520bf8ac8a, type: 3}',
      '  m_StaticBatchInfo:',
      '',
    ].join('\n');
    expect(readMaterialSlots(body)).toEqual([
      { fileID: '-6737146398385819516', guid: '1f281ad732a595143bb574520bf8ac8a' },
    ]);
  });

  it('양수·음수가 섞여도 순서를 지킨다 — 순서가 submesh index 다', () => {
    const body = [
      '  m_Materials:',
      '  - {fileID: 2100000, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 2}',
      '  - {fileID: -8883127529357401494, guid: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, type: 2}',
      '',
    ].join('\n');
    expect(readMaterialSlots(body).map((s) => s.fileID)).toEqual(['2100000', '-8883127529357401494']);
  });

  it('CRLF 에서도 같다 — T-61·T-64 가 같은 자리에서 났다', () => {
    const body = '  m_Materials:\r\n  - {fileID: -1, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 2}\r\n';
    expect(readMaterialSlots(body)).toHaveLength(1);
  });
});

describe('readInstanceMaterialOverrides — 음수 target', () => {
  it('override 대상 renderer 의 fileID 가 음수여도 읽는다', () => {
    const body = [
      '  m_Modifications:',
      '  - target: {fileID: -4823156847213985620, guid: dddddddddddddddddddddddddddddddd, type: 3}',
      "    propertyPath: 'm_Materials.Array.data[1]'",
      '    value:',
      '    objectReference: {fileID: 2100000, guid: 396abb3212f43a445b2b0b0e2b8d1c4e, type: 2}',
      '',
    ].join('\n');
    expect(readInstanceMaterialOverrides(body)).toEqual([
      { targetFileID: '-4823156847213985620', slot: 1, guid: '396abb3212f43a445b2b0b0e2b8d1c4e' },
    ]);
  });
});
