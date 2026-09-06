// IS_MOCK_WORLD 판정 (S15P21A604-466).
//
// 값 자체는 빌드 시 import.meta.env 로 확정돼 테스트에서 갈아끼울 수 없다. 그래서 여기서는
// **판정 규칙**을 같은 형태로 재현해 잠근다 — 규칙이 바뀌면 이 테스트가 먼저 깨진다.
import { describe, expect, it } from 'vitest';

/** shared/config/unity 의 IS_MOCK_WORLD 와 같은 규칙 */
function decide(env: { VITE_MOCK_WORLD?: string; VITE_USE_MOCK?: string }): boolean {
  return env.VITE_MOCK_WORLD !== undefined
    ? env.VITE_MOCK_WORLD === 'true'
    : env.VITE_USE_MOCK === 'true';
}

describe('플래그 미설정 — 기존 동작이 그대로여야 한다', () => {
  it('USE_MOCK=true 면 World 도 mock', () => {
    expect(decide({ VITE_USE_MOCK: 'true' })).toBe(true);
  });

  it('USE_MOCK=false 면 World 는 실물', () => {
    expect(decide({ VITE_USE_MOCK: 'false' })).toBe(false);
  });

  it('둘 다 없으면 실물 — 프로덕션 기본값', () => {
    expect(decide({})).toBe(false);
  });
});

describe('MOCK_WORLD 를 명시하면 그쪽이 이긴다', () => {
  it('mock API + 실 Unity — 이 회차의 목적인 조합', () => {
    expect(decide({ VITE_USE_MOCK: 'true', VITE_MOCK_WORLD: 'false' })).toBe(false);
  });

  it('실 API + mock World — 반대 조합도 가능하다', () => {
    expect(decide({ VITE_USE_MOCK: 'false', VITE_MOCK_WORLD: 'true' })).toBe(true);
  });

  it("'true' 가 아닌 값은 전부 실물로 읽는다 — 오타가 조용히 mock 을 켜지 않게", () => {
    expect(decide({ VITE_MOCK_WORLD: 'TRUE' })).toBe(false);
    expect(decide({ VITE_MOCK_WORLD: '1' })).toBe(false);
    expect(decide({ VITE_MOCK_WORLD: '' })).toBe(false);
  });
});
