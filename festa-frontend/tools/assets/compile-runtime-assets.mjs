// SSAFESTA Runtime Asset Compiler — entrypoint (S15P21A604-480).
//
//   node tools/assets/compile-runtime-assets.mjs
//
// Unity source 를 받아 **원본과 다른 구조의** 런타임 에셋을 만든다. build-booth-assets 가
// 만드는 Intermediate 와는 다른 산출물이다 — 계층·노드명·원본 topology 가 남지 않는다.
//
// 이번 v1 은 대표 입력으로만 돈다. 전 타입 변환은 License Gate 와 도구가 갖춰진 뒤다.
import { resolve } from 'node:path';
import { BOOTH_ASSETS, MATERIAL_PROBES, UNITY_ASSETS_ROOT } from './booth-assets.config.mjs';
import { convert, projectRoot, sharedGuidIndex, warnings } from './source.mjs';
import { compileAsset, formatReport } from './compiler/pipeline.mjs';
import { emitRuntimeManifest } from './compiler/emit.mjs';
import { EMISSION, evaluate } from './compiler/licenseGate.mjs';
import { planTextureOptimization, resolveMaterial } from './compiler/material.mjs';
import { RUNTIME_DIR, ensureDir } from './compiler/paths.mjs';

/** v1 대표 — 조립체 하나 + 텍스처 worst case 하나. 늘리지 않는다 */
const TARGET_ASSET_CODES = ['BOOTH_KIOSK_SURVEY', 'FURN_CHAIR_02_WHITE', 'DISP_BOX_01', 'FURN_CHAIR_01_WHITE'];

function materialPathOf(source) {
  return source.material === undefined ? null : resolve(projectRoot, UNITY_ASSETS_ROOT, source.material);
}

async function main() {
  ensureDir(RUNTIME_DIR);
  const guidIndex = sharedGuidIndex();
  const entries = [];
  let productionBlocked = 0;

  for (const source of BOOTH_ASSETS) {
    if (!TARGET_ASSET_CODES.includes(source.assetCode)) continue;

    // resolve + normalize — -476·-473 이 만든 경로를 그대로 쓴다
    const { root } = convert(source);

    const { report, emitted } = await compileAsset(
      {
        assetCode: source.assetCode,
        objectType: source.objectType,
        sourcePackage: source.sourcePackage,
        typeDefault: source.typeDefault,
        source: root,
        materialPath: materialPathOf(source),
        guidIndex,
      },
      { runtimeDir: RUNTIME_DIR },
    );

    console.log(formatReport(report));
    if (emitted !== null) {
      entries.push(emitted);
      if (emitted.emission !== EMISSION.PRODUCTION_CANDIDATE) productionBlocked += 1;
    }
  }

  // ── 재질 대표 실측 — geometry 와 별개로 텍스처 파이프라인만 본다 ──
  console.log('\n■ material probes');
  const probes = [];
  for (const probe of MATERIAL_PROBES) {
    const license = evaluate(probe.sourcePackage);
    const material = resolveMaterial(resolve(projectRoot, UNITY_ASSETS_ROOT, probe.material), guidIndex);
    const texture = planTextureOptimization(material.textures);
    probes.push({ id: probe.id, license: license.emission, runtime: material.runtime, texture });
    const channels = material.textures.map((t) => t.channel).join(', ') || '없음';
    // 런타임 변환 결과는 위 에셋별 `texture` 줄이 말한다. 여기서 `미적용` 이라고 찍으면
    // 같은 실행 안에서 두 줄이 서로를 부정한다 — probe 는 원본 실측까지만 말한다.
    console.log(
      `  ${probe.id.padEnd(14)} 채널 [${channels}] · 원본 ${texture.sourceBytes} B (런타임 변환은 emit 단계)`,
    );
  }

  const emission = productionBlocked > 0 ? EMISSION.LOCAL_SPIKE_ONLY : EMISSION.PRODUCTION_CANDIDATE;
  const manifestPath = emitRuntimeManifest(entries, { dir: RUNTIME_DIR, emission });

  console.log(`\nruntime manifest: ${manifestPath} (${entries.length} assets, emission=${emission})`);
  if (emission === EMISSION.LOCAL_SPIKE_ONLY) {
    console.log('  → 라이선스 판정 전이라 배포 경로로 내보내지 않는다. .generated/runtime/ 안에만 있다.');
  }
  if (warnings.length > 0) console.log(`경고 ${warnings.length}건 — 위 목록 참조`);
}

await main();
