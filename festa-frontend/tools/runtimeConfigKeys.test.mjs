// runtime-config 키의 1:1 을 코드로 잠근다 (S15P21A604-567).
//
// `docker/40-runtime-config.sh` 는 주석으로 "키 목록은 RuntimeAssetConfig 와 1:1 이다" 라 적어 두었고
// 실제로는 5키 중 2키만 썼다. 그래서 authBaseUrl·aiApiBaseUrl·boothAssetBase 는 배포 환경에서 주입할
// 방법이 없었고, AI Chat 은 같은 이미지를 환경마다 다시 빌드해야 하는 상태로 남아 있었다 —
// -254·-427 이 없애려던 바로 그 구조다. 주석은 어긋나도 조용하지만 이 테스트는 red 가 된다.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const entrypoint = readFileSync(join(root, 'docker/40-runtime-config.sh'), 'utf8');
const runtimeTs = readFileSync(join(root, 'src/shared/config/runtime.ts'), 'utf8');

/** entrypoint 가 실제로 내보내는 키 — `append <키> "${PUBLIC_...}"` 호출에서 뽑는다. */
function entrypointKeys() {
  return [...entrypoint.matchAll(/^\s*append\s+([A-Za-z][A-Za-z0-9]*)\s/gm)].map((m) => m[1]);
}

/** entrypoint 가 읽는 환경변수 이름. */
function entrypointEnvNames() {
  return [...entrypoint.matchAll(/\$\{(PUBLIC_[A-Z0-9_]+):-\}/g)].map((m) => m[1]);
}

/** RuntimeAssetConfig 인터페이스가 선언한 키. */
function typeKeys() {
  const body = /export interface RuntimeAssetConfig \{([\s\S]*?)\n\}/.exec(runtimeTs);
  if (body === null) throw new Error('RuntimeAssetConfig 선언을 찾지 못했다');
  return [...body[1].matchAll(/^\s*([A-Za-z][A-Za-z0-9]*)\??:/gm)].map((m) => m[1]);
}

/** camelCase 키 → entrypoint 가 쓰는 PUBLIC_SNAKE_CASE 이름. */
function toEnvName(key) {
  return `PUBLIC_${key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()}`;
}

describe('runtime-config 키 1:1', () => {
  it('entrypoint 와 RuntimeAssetConfig 가 같은 키 집합을 쓴다', () => {
    expect([...entrypointKeys()].sort()).toEqual([...typeKeys()].sort());
  });

  it('키 이름이 중복되지 않는다', () => {
    const keys = entrypointKeys();
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('환경변수 이름이 키 이름에서 규칙대로 파생된다 — 배포 쪽이 이름을 추측하지 않아도 된다', () => {
    expect(entrypointEnvNames().sort()).toEqual(entrypointKeys().map(toEnvName).sort());
  });

  it('값이 빈 키는 산출물에 넣지 않는다 — "주입했는데 비었다" 와 "주입하지 않았다" 를 구분한다', () => {
    // append 가 빈 값에서 조기 반환하는 것이 그 계약이다.
    expect(entrypoint).toMatch(/if \[ -z "\$value" \]; then[\s\S]*?return 0/);
  });

  it('각 키의 미지정을 기동 로그로 남긴다 — 조용한 누락을 만들지 않는다', () => {
    expect(entrypoint).toMatch(/echo "\[runtime-config\] \$key 미지정/);
  });
});
