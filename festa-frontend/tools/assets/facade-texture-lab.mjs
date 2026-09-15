// Facade 텍스처 감축 실험 (S15P21A604-552).
//
//   node tools/assets/facade-texture-lab.mjs
//
// geometry 실험에서 드러난 것: **가벼운 자산일수록 텍스처가 병목이다.** HIGH_STRIKER 는
// GLB 88 KB 중 54 KB(61%)가 텍스처라, ratio 를 아무리 낮춰도 파일이 안 줄었다.
//
// 여기서 두 축을 잰다.
//   ① 해상도 — 512(현 기본) → 256 → 128
//   ② map 구성 — 채널별 바이트를 실측해 "normal 을 빼면 얼마" 를 계산이 아니라 수치로 답한다
//
// 선택 UI 는 작은 카드 썸네일과 한 번의 미리보기가 전부다. 내부 자산과 같은 해상도를
// 쓸 이유가 없다.
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CARNIVAL_PREFAB_ROOT, CM_TO_M, FACADE_ASSETS } from './facade-assets.config.mjs';
import { boundsOf, loadFbx, projectRoot, sharedGuidIndex, warn } from './source.mjs';
import { loadPrefab } from './unity-prefab.mjs';
import { compileAsset } from './compiler/pipeline.mjs';
import * as THREE from 'three';

const SAMPLES = [
  'FACADE_BOOTH_HIGH_STRIKER', // 텍스처 비중 61% — 최악
  'FACADE_CART_POPCORN', // 37%
  'FACADE_STAND_FUNNEL_CAKE', // 28% · 재질 3 · 텍스처 9 로 가장 복잡
  'FACADE_BOOTH_RING_TOSS', // 5% — 큰 자산에서는 텍스처가 문제가 아님을 확인
];
const SIZES = [512, 256, 128];
const LAB_DIR = resolve(projectRoot, '.generated/facade-lab');

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

async function main() {
  mkdirSync(LAB_DIR, { recursive: true });
  const guidIndex = sharedGuidIndex();
  const rows = [];

  for (const code of SAMPLES) {
    const source = FACADE_ASSETS.find((a) => a.assetCode === code);
    console.log(`\n■ ${code} — ${source.displayName}`);
    console.log('  maxSize   GLB bytes   텍스처B   채널별 바이트');
    console.log('  ' + '-'.repeat(76));

    for (const maxSize of SIZES) {
      const { report, emitted } = await compileAsset(
        {
          assetCode: `${code}__tex${maxSize}`,
          objectType: 'DECORATION',
          sourcePackage: 'CarnivalKit',
          typeDefault: false,
          source: loadFacade(source),
          materialPath: null,
          guidIndex,
        },
        // geometry 는 0.25 로 고정한다 — 텍스처 축만 움직이려면 다른 축을 묶어야 한다
        { runtimeDir: LAB_DIR, simplifyRatio: 0.25, maxTextureSize: maxSize },
      );
      if (emitted === null) continue;

      const byChannel = {};
      for (const t of report.stages.material.textures) {
        byChannel[t.channel] = (byChannel[t.channel] ?? 0) + t.runtimeBytes;
      }
      const total = report.stages.texture.runtimeBytes;
      rows.push({ assetCode: code, maxSize, bytes: report.stages.emit.bytes, textureBytes: total, byChannel });

      console.log(
        '  ' +
          String(maxSize).padEnd(10) +
          String(report.stages.emit.bytes).padStart(10) +
          String(total).padStart(10) +
          '   ' +
          Object.entries(byChannel)
            .map(([c, b]) => `${c} ${b}`)
            .join(' · '),
      );
    }
  }

  // ── map 제거 효과 — 채널별 실측치에서 바로 나온다 ──────────────
  console.log('\n■ map 제거 효과 (512 기준) — baseColor 만 남기면');
  console.log('  assetCode                        전체B   baseColor만B    감축');
  console.log('  ' + '-'.repeat(70));
  for (const r of rows.filter((x) => x.maxSize === 512)) {
    const base = r.byChannel.baseColor ?? 0;
    console.log(
      '  ' +
        r.assetCode.padEnd(32) +
        String(r.textureBytes).padStart(8) +
        String(base).padStart(14) +
        `   ${(100 - (base / r.textureBytes) * 100).toFixed(1)}%`.padStart(9),
    );
  }

  writeFileSync(resolve(LAB_DIR, 'texture-results.json'), JSON.stringify(rows, null, 2));
  console.log(`\n  → ${resolve(LAB_DIR, 'texture-results.json')}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
