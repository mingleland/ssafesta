// Booth Studio 팔레트의 assetCode 정합 (S15P21A604-509, GitLab #146·#154).
//
// **왜 이 테스트가 있나.** 팔레트가 내보낸 `assetCode` 가 Unity 레지스트리에 없으면 월드는
// `Unknown assetCode … 타입 기본 자산으로 대체` 경고를 찍고 상자를 놓는다. 저장은 성공하고
// 화면만 다르므로 아무도 모른다 — 사용자는 "패널을 놓았는데 상자가 나온다" 를 겪는다(#154 실측).
// 런타임 fallback(`pickAsset` 의 type default)은 안전장치로 그대로 두고, **정본 불일치는 여기서 red** 로 만든다.
//
// 정본의 단일 출처는 Unity `BoothObjectRegistry.asset` 파일 자체다. FE 에 목록을 복제하면
// 그것이 다섯 번째 어휘가 된다(-509 가 정리하려는 것이 정확히 그 상태였다).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const registry = readFileSync(
  join(root, '../festa-unity/Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset'),
  'utf8',
);
const palette = readFileSync(join(root, 'src/features/studio/model/visualAssets.ts'), 'utf8');
const templates = readFileSync(join(root, 'src/features/studio/model/boothTemplates.ts'), 'utf8');
const pipelineConfig = readFileSync(join(root, 'tools/assets/booth-assets.config.mjs'), 'utf8');

/** 레지스트리가 아는 모든 코드 — canonical + 레거시 별칭 + 예약. 빈 코드(typeDefault)는 뺀다. */
function registryCodes() {
  return new Set([...registry.matchAll(/^\s*assetCode: (\S+)\s*$/gm)].map((m) => m[1]));
}

/**
 * 게임 파트가 **한 릴리스 뒤 정리하겠다** 고 한 레거시 별칭 7종 (#154).
 * 팔레트가 이 코드로 되돌아가면 red — 별칭이 사라지는 순간 다시 상자가 된다.
 * 예외는 아래 LEGACY_ALLOWED 에만 둔다.
 */
const LEGACY_ALIASES = new Set([
  'WALL_PLAIN', 'COUNTER_GRAPHIC', 'SHELF', 'TRUSS_BEAM', 'TRUSS_PILLAR', 'TRUSS_GATE', 'PLANT',
]);

/**
 * 2층에서만 쓰는 예외 — **파이프라인에 없어도 되는 코드.** 이유가 있어야 여기 들어온다.
 *
 * DECOR_PLANT_01: 레지스트리에는 정본 29행으로 등록돼 있다(#154 A안, 게임 파트 결정 2026-09-10).
 * 그런데 `Decor/PLANT.prefab` 이 감싸는 원본은 ExpoKit 이 아니라
 * `Assets/Palmov Island/Low Poly Houses Free Pack/Prefabs/Trees/potted tree.prefab` 이고,
 * 그 팩은 `tools/assets/source-packs.lock.json` 에 선언돼 있지 않다. `compiler/licenseGate.mjs` 의
 * `evaluate` 는 **선언되지 않은 package 를 compile 하지 않는다** — 그래서 파이프라인에 항목만 넣으면
 * 이 테스트만 통과하고 실제 변환은 안 되는 반쪽이 된다.
 *
 * 즉 이것은 1층(레지스트리 정합) 문제가 아니라 **라이선스 게이트로 막힌 항목**이고,
 * `-509` 완료조건 ④("라이선스 게이트로 막힌 항목은 '막혔다'가 코드가 아니라 기록으로 남는다")가
 * 예정해 둔 자리다. 취득·라이선스 근거가 확인되면 lock 선언과 파이프라인 반입을 후속 검토하고
 * 그때 이 예외를 지운다.
 */
const PIPELINE_EXEMPT = new Set(['DECOR_PLANT_01']);

function paletteCodes() {
  return [...palette.matchAll(/assetCode: '([A-Z_0-9]+)'/g)].map((m) => m[1]);
}
function templateCodes() {
  return [...templates.matchAll(/assetCode: '([A-Z_0-9]+)'/g)].map((m) => m[1]);
}
function pipelineCodes() {
  return new Set([...pipelineConfig.matchAll(/assetCode: '([A-Z_0-9]+)'/g)].map((m) => m[1]));
}

describe('1층 — 팔레트 코드가 Unity 레지스트리에 있는가', () => {
  it('팔레트의 모든 assetCode 가 레지스트리에 등록돼 있다', () => {
    const known = registryCodes();
    expect(paletteCodes().filter((c) => !known.has(c))).toEqual([]);
  });

  it('빠른 시작 템플릿의 코드도 마찬가지다 — 팔레트만 보다 템플릿을 놓치지 않는다', () => {
    const known = registryCodes();
    expect(templateCodes().filter((c) => !known.has(c))).toEqual([]);
  });

  it('레거시 별칭으로 되돌아가지 않는다 — 별칭은 한 릴리스 뒤 정리된다 (#154)', () => {
    // 예외 없이 본다. 팔레트에 남아 있던 마지막 별칭 PLANT 가 DECOR_PLANT_01 로 옮겨졌다.
    expect(paletteCodes().filter((c) => LEGACY_ALIASES.has(c))).toEqual([]);
  });
});

describe('2층 — 팔레트 코드가 파이프라인 산출물과 1:1 인가', () => {
  // -509 완료조건 ②: "팔레트에 assetCode 가 붙은 항목이 파이프라인 산출물과 1:1 로 연결된다".
  // canonical 28 전부를 파이프라인에 넣으라는 뜻이 아니다 — 팔레트가 참조하는 것이 대상이다.
  it('팔레트가 쓰는 코드는 booth-assets.config.mjs 에도 있다', () => {
    const built = pipelineCodes();
    const missing = paletteCodes().filter((c) => !built.has(c) && !PIPELINE_EXEMPT.has(c));
    expect(missing).toEqual([]);
  });

  it('예외 목록은 실제로 쓰이는 것만 둔다 — 죽은 예외가 쌓이지 않게', () => {
    const used = new Set(paletteCodes());
    expect([...PIPELINE_EXEMPT].filter((c) => !used.has(c))).toEqual([]);
  });

  it('파이프라인이 만드는 코드도 레지스트리에 있다 — 반대 방향도 어긋나지 않는다', () => {
    const known = registryCodes();
    expect([...pipelineCodes()].filter((c) => !known.has(c))).toEqual([]);
  });
});
