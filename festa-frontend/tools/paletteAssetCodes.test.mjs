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
 * 아직 정본 코드로 옮기지 못한 것 — **이유가 있어야 여기 들어온다.**
 *
 * PLANT: Unity 에 `Decor/PLANT.prefab`(저폴리 화분 래퍼)이 실재하는데 정본 28행에는 화분 대응
 * 코드가 없다. canonical 신규 정의는 정본 표 갱신이라 게임 파트 결정이고 #154 에 요청해 둔 상태다.
 * 결정이 오면 이 예외를 지우고 팔레트를 그 코드로 바꾼다.
 */
const LEGACY_ALLOWED = new Set(['PLANT']);

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
    const used = paletteCodes().filter((c) => LEGACY_ALIASES.has(c) && !LEGACY_ALLOWED.has(c));
    expect(used).toEqual([]);
  });

  it('예외 목록은 실제로 쓰이는 것만 둔다 — 죽은 예외가 쌓이지 않게', () => {
    const used = new Set(paletteCodes());
    expect([...LEGACY_ALLOWED].filter((c) => !used.has(c))).toEqual([]);
  });
});

describe('2층 — 팔레트 코드가 파이프라인 산출물과 1:1 인가', () => {
  // -509 완료조건 ②: "팔레트에 assetCode 가 붙은 항목이 파이프라인 산출물과 1:1 로 연결된다".
  // canonical 28 전부를 파이프라인에 넣으라는 뜻이 아니다 — 팔레트가 참조하는 것이 대상이다.
  it('팔레트가 쓰는 코드는 booth-assets.config.mjs 에도 있다', () => {
    const built = pipelineCodes();
    const missing = paletteCodes().filter((c) => !built.has(c) && !LEGACY_ALLOWED.has(c));
    expect(missing).toEqual([]);
  });

  it('파이프라인이 만드는 코드도 레지스트리에 있다 — 반대 방향도 어긋나지 않는다', () => {
    const known = registryCodes();
    expect([...pipelineCodes()].filter((c) => !known.has(c))).toEqual([]);
  });
});
