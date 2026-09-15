// assetCode 단위 AABB 정본 잠금 (S15P21A604-785, GitLab #181).
//
// **왜 커밋된 lock 인가.** runtime manifest 는 산출물이라 저장소에 없다 — 없는 것이 기본 상태다.
// 그래서 manifest 를 정본으로 삼으면 CI 와 fresh clone 에서 대조 대상 자체가 사라진다(BE 지적,
// 2026-09-15 13:56). 값을 `asset-bounds.lock.json` 에 고정하고 manifest 가 거기서 벗어나면
// `assets:verify` 가 멈춘다.
//
// **이 파일이 보는 것은 lock 자체의 정합**이다. manifest ↔ lock 대조는 `assets:verify` 몫이라
// 여기서 다시 하지 않는다 — front-test 에는 manifest 가 없다(`ci/test` 가 `assets:build` 를
// 돌리지 않는다). 두 층이 각자 상대가 못 보는 것을 본다.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BOOTH_ASSETS } from './assets/booth-assets.config.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const lock = JSON.parse(readFileSync(join(here, 'assets/asset-bounds.lock.json'), 'utf8'));

describe('lock 형식', () => {
  it('지원하는 형식이다 — 형식이 바뀌면 verify 가 조용히 통과하지 않게', () => {
    expect(lock.version).toBe(1);
    expect(lock.kind).toBe('ssafesta-asset-bounds-lock');
  });

  it('모든 항목이 min·max 3값이다', () => {
    for (const [code, b] of Object.entries(lock.assets)) {
      expect(b.min, code).toHaveLength(3);
      expect(b.max, code).toHaveLength(3);
      for (const v of [...b.min, ...b.max]) expect(typeof v, code).toBe('number');
    }
  });

  it('바닥에 선다 — min.y 가 0 이다. 계약 원점이 바닥 중앙이라 여기서 뜨면 공중에 뜬다', () => {
    for (const [code, b] of Object.entries(lock.assets)) {
      expect(b.min[1], code).toBe(0);
    }
  });

  it('min 이 max 보다 크지 않다', () => {
    for (const [code, b] of Object.entries(lock.assets)) {
      for (const i of [0, 1, 2]) expect(b.min[i], code).toBeLessThanOrEqual(b.max[i]);
    }
  });
});

describe('lock 과 파이프라인이 1:1 이다', () => {
  const built = BOOTH_ASSETS.map((a) => a.assetCode).sort();
  const locked = Object.keys(lock.assets).sort();

  it('파이프라인이 굽는 자산은 전부 lock 에 있다 — 없으면 대조 없이 배포된다', () => {
    expect(built.filter((c) => !locked.includes(c))).toEqual([]);
  });

  it('lock 에만 있는 죽은 항목이 없다 — 지운 자산의 치수가 남지 않게', () => {
    expect(locked.filter((c) => !built.includes(c))).toEqual([]);
  });
});

