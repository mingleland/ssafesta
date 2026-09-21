// Game Studio 서버 저작 플래그의 소유권을 코드로 잠근다 (GitLab #258).
//
// #245 는 배포 빌드에서 서버 저작이 꺼져 저장이 조용히 브라우저로 빠지는 사고를 `ci/build` 가
// `VITE_GAME_STUDIO_API_ENABLED=true` 를 주입해 막았다. 기능은 살았지만 제품 기능의 on/off 를 CI
// 스크립트가 쥐게 됐고, 그 스크립트 변경은 shared-ci 로 분류돼 Front build/deploy 를 직접 유발하지도
// 않는다. #258 에서 기본값을 제품 정본(runtimeConfig.ts)으로 옮겼다.
//
// 행동 계약(undefined/true/false)은 src/game-studio 쪽 단위 테스트가 지키지만 그 경로는 원격 CI 에서
// 제외된다(ci/test, S15P21A604-696). 그래서 '누가 소유하는가' 만큼은 CI 가 도는 여기서 잠근다.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const runtimeConfig = readFileSync(join(root, 'src/game-studio/app/runtimeConfig.ts'), 'utf8');
// 주석으로 플래그를 설명하는 건 괜찮다 — 실행되는 줄이 주입하면 안 된다.
const ciBuildCode = readFileSync(join(root, '../ci/build'), 'utf8')
  .split('\n')
  .filter((line) => !line.trim().startsWith('#'))
  .join('\n');

describe('Game Studio 서버 저작 플래그 소유권 (#258)', () => {
  it('기본값은 제품 정본이 ON 으로 쥔다 — 값이 없으면 켜진다', () => {
    expect(runtimeConfig).toContain("import.meta.env.VITE_GAME_STUDIO_API_ENABLED !== 'false'");
    expect(runtimeConfig).not.toContain("VITE_GAME_STUDIO_API_ENABLED === 'true'");
  });

  it('ci/build 는 이 플래그를 주입하지 않는다', () => {
    expect(ciBuildCode).not.toMatch(/VITE_GAME_STUDIO_API_ENABLED\s*=/);
  });
});
