// Facade 경량화 실험 (S15P21A604-552).
//
//   node tools/assets/facade-simplify-lab.mjs [--samples A,B] [--ratios 1,0.5,0.25]
//
// **최종 tri 상한을 먼저 정하지 않는다.** 단계별 수치를 다 찍어 놓고 눈으로 보고 정한다 —
// 목표치를 먼저 박으면 그 숫자에 맞추려고 품질 판정을 굽히게 된다.
//
// 여기서 만드는 GLB 는 `.generated/facade-lab/` 에 들어간다. 런타임 산출물이 아니다.
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CARNIVAL_PREFAB_ROOT, CM_TO_M, FACADE_ASSETS } from './facade-assets.config.mjs';
import { boundsOf, loadFbx, projectRoot, sharedGuidIndex, warn } from './source.mjs';
import { loadPrefab } from './unity-prefab.mjs';
import { compileAsset } from './compiler/pipeline.mjs';
import * as THREE from 'three';

/** 표본 7종 — 밀도 스펙트럼 · 단일/nested · 서로 다른 실루엣을 모두 덮는다 */
const DEFAULT_SAMPLES = [
  'FACADE_BOOTH_RING_TOSS', // 최고밀도 181,818 tri · nested PF_Combined · 130 mesh
  'FACADE_BOOTH_WATER_GUN', // 고밀도 158,266 tri · 가장 큰 실루엣 5.6×7.5×7.1 m
  'FACADE_STAND_CANDY_APPLE', // 중간 48,818 tri · 단일 prefab · 9 mesh
  'FACADE_BOOTH_PRIZE_WALL', // 중간 34,928 tri · 인형 15개 — 작은 덩어리가 실루엣의 전부다
  'FACADE_STAND_FUNNEL_CAKE', // 19,636 tri · 재질 3 · 텍스처 9 (20.65 MB) — 텍스처 worst case
  'FACADE_CART_POPCORN', // 경량 6,314 tri · 5 mesh
  'FACADE_BOOTH_HIGH_STRIKER', // 초경량 1,161 tri · 2 mesh — 더 줄일 게 남았는지 본다
];

/** 원본 → 5% 까지. `null` 은 simplify 안 함(원본 대조군) */
const DEFAULT_RATIOS = [null, 0.5, 0.25, 0.1, 0.05];

const LAB_DIR = resolve(projectRoot, '.generated/facade-lab');
const round = (n) => Number(n.toFixed(3));

/** prefab → 피벗·축을 맞춘 three 씬. 내부 자산의 convert() 와 같은 규약을 쓴다 */
function loadFacade(source) {
  const raw = loadPrefab(resolve(projectRoot, CARNIVAL_PREFAB_ROOT, source.prefab), {
    guidIndex: sharedGuidIndex(),
    unitScale: CM_TO_M,
    loadFbx,
    warn,
  });
  const root = new THREE.Group();
  root.add(raw);
  const b = boundsOf(root);
  raw.position.set(-(b.min[0] + b.max[0]) / 2, -b.min[1], -(b.min[2] + b.max[2]) / 2);
  return root;
}

const sizeOf = (b) => [round(b.max[0] - b.min[0]), round(b.max[1] - b.min[1]), round(b.max[2] - b.min[2])];

async function main() {
  const arg = (name) => {
    const i = process.argv.indexOf(name);
    return i === -1 ? null : process.argv[i + 1];
  };
  const sampleCodes = arg('--samples')?.split(',') ?? (process.argv.includes('--all') ? FACADE_ASSETS.map((a) => a.assetCode) : DEFAULT_SAMPLES);
  const ratios = arg('--ratios')?.split(',').map((r) => (r === 'null' ? null : Number(r))) ?? DEFAULT_RATIOS;
  const maxTextureSize = arg('--tex') === null ? undefined : Number(arg('--tex'));

  mkdirSync(LAB_DIR, { recursive: true });
  const guidIndex = sharedGuidIndex();
  const results = [];

  for (const code of sampleCodes) {
    const source = FACADE_ASSETS.find((a) => a.assetCode === code);
    if (source === undefined) {
      console.error(`  ! ${code} 가 FACADE_ASSETS 에 없다`);
      continue;
    }

    console.log(`\n■ ${code} — ${source.displayName}`);
    console.log('  ratio      tri      GLB bytes    텍스처B    compile   크기(m)');
    console.log('  ' + '-'.repeat(78));

    let baseTri = null;
    let baseBytes = null;

    for (const ratio of ratios) {
      // 씬은 매번 새로 만든다 — compileAsset 이 재질·geometry 를 건드리므로 재사용하면 오염된다
      const root = loadFacade(source);
      const started = Date.now();
      const { report, emitted } = await compileAsset(
        {
          assetCode: `${code}__${ratio === null ? 'orig' : String(ratio).replace('.', '_')}`,
          objectType: 'DECORATION',
          sourcePackage: 'CarnivalKit',
          typeDefault: false,
          source: root,
          materialPath: null,
          guidIndex,
        },
        { runtimeDir: LAB_DIR, simplifyRatio: ratio === null ? null : ratio, maxTextureSize },
      );
      const ms = Date.now() - started;

      if (emitted === null) {
        console.log(`  ${String(ratio).padEnd(8)} 컴파일 안 함 — ${report.stages.license.reason}`);
        continue;
      }

      const tri = report.stages.geometry.after.triangles;
      const bytes = report.stages.emit.bytes;
      const texBytes = report.stages.texture.runtimeBytes;
      baseTri ??= tri;
      baseBytes ??= bytes;

      const row = {
        assetCode: code,
        ratio,
        triangles: tri,
        triPct: round((tri / baseTri) * 100),
        bytes,
        bytesPct: round((bytes / baseBytes) * 100),
        textureBytes: texBytes,
        compileMs: ms,
        size: sizeOf(report.stages.emit.bounds),
      };
      results.push(row);

      console.log(
        '  ' +
          String(ratio ?? 'orig').padEnd(9) +
          String(tri).padStart(8) +
          String(bytes).padStart(13) +
          String(texBytes).padStart(11) +
          `${ms}ms`.padStart(10) +
          '   ' +
          row.size.join(' × ') +
          (ratio === null ? '' : `   tri ${row.triPct}% · bytes ${row.bytesPct}%`),
      );
    }
  }

  const out = resolve(LAB_DIR, 'results.json');
  writeFileSync(out, JSON.stringify(results, null, 2));
  console.log(`\n  → ${out}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
