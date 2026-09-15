// Unity `.prefab` override 를 CRLF 에서도 읽는지 (S15P21A604-476).
//
// Unity 가 저장한 `.prefab` 은 Windows 에서 CRLF 다. `instanceTransform` 의 정규식이 `\n` 만
// 요구하면 **모든 override 가 매치에 실패하고 조용히 기본값으로 떨어진다** — 위치 0, 회전 항등,
// 배율 1. 오류도 경고도 없이 조립체가 원점에 겹쳐 쌓이고, SURVEY_KIOSK 가
// `0.62 × 0.9255 × 0.3195` 대신 `0.5855 × 0.2794 × 0.9022`(누운 모양)로 나왔다.
//
// `loadPrefab` 은 파일과 FBX 로더를 물고 있어 단위 테스트가 무겁다. 그래서 같은 텍스트를
// 두 줄바꿈으로 넣어 **파싱 결과가 같은지**만 본다 — 결함이 정확히 그 자리였다.
import { describe, expect, it } from 'vitest';
import { readInstanceTransformForTest } from '../unity-prefab.mjs';

const LF = [
  'PrefabInstance:',
  '  m_Modifications:',
  '  - target: {fileID: 1, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 3}',
  '    propertyPath: m_LocalPosition.y',
  '    value: 0.9233265',
  '  - target: {fileID: 1, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 3}',
  '    propertyPath: m_LocalRotation.y',
  '    value: 0.7071068',
  '',
].join('\n');

describe('instanceTransform — CRLF 로 저장된 prefab', () => {
  it('LF 와 CRLF 가 같은 값을 준다', () => {
    const lf = readInstanceTransformForTest(LF);
    const crlf = readInstanceTransformForTest(LF.replace(/\n/g, '\r\n'));
    expect(lf.position.y).toBeCloseTo(0.9233265, 7);
    expect(crlf).toEqual(lf);
  });

  it('CRLF 에서도 override 가 기본값으로 떨어지지 않는다 — 이게 키오스크가 누운 원인이었다', () => {
    const t = readInstanceTransformForTest(LF.replace(/\n/g, '\r\n'));
    expect(t.position.y).not.toBe(0);
    expect(t.rotation.y).toBeCloseTo(0.7071068, 7);
  });
});
