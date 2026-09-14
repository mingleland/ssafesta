// 산출물 경로 — Intermediate 와 Runtime 을 역할로 가른다 (S15P21A604-480).
//
// 둘은 성질이 다르다. 섞어 두면 "이 GLB 를 배포해도 되나" 를 파일마다 판정해야 한다.
//
//   Intermediate   Unity source 를 Compiler 가 먹을 수 있게 옮긴 것. 원본 구조에 가깝다.
//                  build-time 전용이며 git·production Docker·production serving 전부 금지.
//   Runtime        Compiler 결과. 원본 topology·metadata 를 그대로 갖지 않는다.
//                  production candidate 이지만 이번 Spike 에서는 배포하지 않는다.
//
// 두 디렉터리 모두 `.gitignore` 로 막는다. `.generated/` 라는 이름 자체가 "손으로 만든 것이
// 아니다" 를 말하도록 골랐다.
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** festa-frontend/ */
export const PROJECT_ROOT = resolve(here, '../../..');

export const GENERATED_ROOT = resolve(PROJECT_ROOT, '.generated');
export const INTERMEDIATE_DIR = resolve(process.env.FESTA_INTERMEDIATE_DIR ?? resolve(GENERATED_ROOT, 'intermediate'));
/** Vite가 dev와 production build 모두 같은 URL로 복사하는 최종 runtime 산출물. */
export const RUNTIME_DIR = resolve(process.env.FESTA_RUNTIME_DIR ?? resolve(PROJECT_ROOT, 'public/assets/booth-runtime'));

export function ensureDir(path) {
  mkdirSync(path, { recursive: true });
  return path;
}
