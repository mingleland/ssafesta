// 개발자 진입 플래그 판정 (S15P21A604-467).
//
// 값은 빌드 시 import.meta.env 로 확정돼 테스트에서 갈아끼울 수 없다. 그래서 **판정 규칙**을
// 같은 형태로 재현해 잠근다 — 특히 "플래그만으로는 켜지지 않는다" 는 성질이 핵심이다.
import { describe, expect, it } from 'vitest';

/** devEntry.ts 의 IS_DEV_ENTRY 와 같은 규칙 */
function decide(env: { DEV: boolean; VITE_DEV_ENTRY?: string }): boolean {
  return env.DEV && env.VITE_DEV_ENTRY === 'true';
}

describe('프로덕션에서는 어떤 값이어도 켜지지 않는다', () => {
  it.each(['true', 'false', undefined])('DEV=false · VITE_DEV_ENTRY=%s → 꺼짐', (flag) => {
    expect(decide({ DEV: false, VITE_DEV_ENTRY: flag })).toBe(false);
  });
});

describe('dev 빌드에서도 명시해야 켜진다', () => {
  it('플래그가 없으면 꺼짐 — 기본값은 제품 동선이다', () => {
    expect(decide({ DEV: true })).toBe(false);
  });

  it("'true' 일 때만 켜진다", () => {
    expect(decide({ DEV: true, VITE_DEV_ENTRY: 'true' })).toBe(true);
  });

  it("'true' 가 아닌 값은 전부 꺼짐 — 오타가 조용히 켜지 않게", () => {
    expect(decide({ DEV: true, VITE_DEV_ENTRY: 'TRUE' })).toBe(false);
    expect(decide({ DEV: true, VITE_DEV_ENTRY: '1' })).toBe(false);
    expect(decide({ DEV: true, VITE_DEV_ENTRY: '' })).toBe(false);
  });
});
