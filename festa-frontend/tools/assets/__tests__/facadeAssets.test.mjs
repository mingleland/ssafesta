// Facade 자산 목록의 계약 (S15P21A604-552).
//
// 여기서 잠그는 것은 **"18종을 그대로 내지 않는다"** 는 성질이다. 실측에서 둘이 걸렸고
// (§12-A-8), 그 판정이 조용히 사라지면 소품 세트(`PRIZE_WALL`)와 부품이 10 m 흩어진 것
// (`HOT_DOG`)이 사용자 선택 목록에 그대로 올라간다.
import { describe, expect, it } from 'vitest';
import {
  CM_TO_M,
  FACADE_ASSETS,
  FACADE_GROUP_LABEL,
  FACADE_PROVIDABLE,
} from '../facade-assets.config.mjs';

describe('FACADE_ASSETS', () => {
  it('assetCode 가 유일하다', () => {
    const codes = FACADE_ASSETS.map((a) => a.assetCode);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('prefab 경로가 유일하다 — 같은 자산에 두 코드를 주지 않는다', () => {
    const prefabs = FACADE_ASSETS.map((a) => a.prefab);
    expect(new Set(prefabs).size).toBe(prefabs.length);
  });

  it('모든 코드가 FACADE_ 로 시작한다 — domain 이 접두로 갈린다', () => {
    for (const a of FACADE_ASSETS) expect(a.assetCode.startsWith('FACADE_'), a.assetCode).toBe(true);
  });

  it('group 이 라벨 표에 있다', () => {
    for (const a of FACADE_ASSETS) expect(Object.keys(FACADE_GROUP_LABEL)).toContain(a.group);
  });

  it('displayName 이 비어 있지 않다 — 선택 UI 가 이것을 보여 준다', () => {
    for (const a of FACADE_ASSETS) expect(a.displayName.length, a.assetCode).toBeGreaterThan(0);
  });
});

describe('선별 — 18종을 그대로 확정하지 않는다', () => {
  it('걸린 자산은 이유를 함께 갖는다 — 판정만 붙이고 근거를 안 남기지 않는다', () => {
    for (const a of FACADE_ASSETS) {
      if (a.triage === undefined) continue;
      expect(['FIX', 'PROP_ONLY'], a.assetCode).toContain(a.triage);
      expect(a.triageNote?.length ?? 0, a.assetCode).toBeGreaterThan(0);
    }
  });

  it('제공 가능 목록에서 걸린 자산이 빠진다', () => {
    const codes = FACADE_PROVIDABLE.map((a) => a.assetCode);
    expect(codes).not.toContain('FACADE_BOOTH_PRIZE_WALL'); // 벽 없이 인형 15개뿐
    expect(codes).not.toContain('FACADE_CART_HOT_DOG'); // 부품이 10.04 m 떨어져 있다
  });

  it('제공 가능 + 걸린 것 = 전체', () => {
    const held = FACADE_ASSETS.filter((a) => a.triage !== undefined);
    expect(FACADE_PROVIDABLE.length + held.length).toBe(FACADE_ASSETS.length);
  });
});

describe('CM_TO_M', () => {
  it('cm 단위다 — FBX 헤더 UnitScaleFactor 1.0 실측', () => {
    // ExpoKit 은 2.54(inch) 라 INCH_TO_M 을 쓴다. `.meta` 는 양쪽 다 useFileScale: 1 이라
    // import 설정만 봐서는 갈리지 않는다 — 헤더가 유일한 근거다
    expect(CM_TO_M).toBe(0.01);
  });
});
