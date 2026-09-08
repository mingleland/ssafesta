// Facade 18종 원본 실측 (S15P21A604-552).
//
//   node tools/assets/facade-inventory.mjs [--json <path>]
//
// 경량화 실험의 **분모**를 만든다. 표본을 고르려면 먼저 18종이 각각 얼마나 무거운지
// 알아야 하고, 그 수치가 없으면 "가장 무거운 것" 을 짐작으로 고르게 된다.
//
// 여기서는 컴파일하지 않는다 — prefab 을 풀어 삼각형·재질·텍스처·AABB 만 센다.
// License Gate 는 산출물을 낼 때 판정하는 것이라 계측에는 걸리지 않는다.
import { existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CARNIVAL_PREFAB_ROOT, CM_TO_M, FACADE_ASSETS, FACADE_GROUP_LABEL } from './facade-assets.config.mjs';
import { boundsOf, loadFbx, projectRoot, sharedGuidIndex, warn } from './source.mjs';
import { loadPrefab } from './unity-prefab.mjs';

const round = (n) => Number(n.toFixed(3));
const mb = (b) => (b / 1024 / 1024).toFixed(2);

/** prefab 하나를 풀어 계측한다. 실패는 삼키지 않고 그대로 낸다 */
function measurePrefab(source, guidIndex) {
  const abs = resolve(projectRoot, CARNIVAL_PREFAB_ROOT, source.prefab);
  if (!existsSync(abs)) throw new Error(`prefab 이 없다: ${abs}`);

  const started = Date.now();
  const root = loadPrefab(abs, { guidIndex, unitScale: CM_TO_M, loadFbx, warn });
  const loadMs = Date.now() - started;

  let triangles = 0;
  let meshes = 0;
  const materialPaths = new Set();
  const texturePaths = new Set();

  root.traverse((o) => {
    if (!o.isMesh) return;
    meshes += 1;
    const pos = o.geometry.getAttribute('position');
    triangles += (o.geometry.index ? o.geometry.index.count : pos.count) / 3;
    for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
      const p = m?.userData?.unityMaterialPath;
      if (typeof p === 'string') materialPaths.add(p);
    }
  });

  // 재질이 참조하는 텍스처는 `.mat` 을 열어야 나온다. 여기서는 파일 크기만 필요하므로
  // resolveMaterial 을 부르지 않고 guid 참조를 직접 훑는다 — 계측이 컴파일을 끌고 오지 않게.
  for (const matPath of materialPaths) {
    let body;
    try {
      body = readFileSync(matPath, 'utf8');
    } catch {
      continue;
    }
    for (const m of body.matchAll(/guid: ([0-9a-f]{32})/g)) {
      const target = guidIndex.get(m[1]);
      if (target !== undefined && /\.(png|jpe?g|tga|psd)$/i.test(target)) texturePaths.add(target);
    }
  }

  let textureBytes = 0;
  for (const t of texturePaths) {
    try {
      textureBytes += statSync(t).size;
    } catch {
      /* 원본이 없으면 0 으로 둔다 — 없는 것을 있는 척하지 않는다 */
    }
  }

  const b = boundsOf(root);
  return {
    assetCode: source.assetCode,
    displayName: source.displayName,
    group: source.group,
    prefab: source.prefab,
    triangles: Math.round(triangles),
    meshes,
    materials: materialPaths.size,
    textures: texturePaths.size,
    textureBytes,
    loadMs,
    size: [round(b.max[0] - b.min[0]), round(b.max[1] - b.min[1]), round(b.max[2] - b.min[2])],
  };
}

async function main() {
  const guidIndex = sharedGuidIndex();
  const rows = [];
  const failed = [];

  for (const source of FACADE_ASSETS) {
    try {
      rows.push(measurePrefab(source, guidIndex));
    } catch (error) {
      failed.push({ assetCode: source.assetCode, error: String(error.message ?? error) });
    }
  }

  rows.sort((a, b) => b.triangles - a.triangles);

  console.log('\n■ Facade 18종 원본 실측 — triangles 내림차순\n');
  console.log('  assetCode                        그룹      tri       mesh  mat  tex  텍스처MB  크기(m)');
  console.log('  ' + '-'.repeat(104));
  for (const r of rows) {
    console.log(
      '  ' +
        r.assetCode.padEnd(32) +
        FACADE_GROUP_LABEL[r.group].padEnd(9) +
        String(r.triangles).padStart(8) +
        String(r.meshes).padStart(6) +
        String(r.materials).padStart(5) +
        String(r.textures).padStart(5) +
        mb(r.textureBytes).padStart(10) +
        '  ' +
        r.size.join(' × '),
    );
  }

  const total = rows.reduce((s, r) => s + r.triangles, 0);
  console.log('  ' + '-'.repeat(104));
  console.log(`  합계 ${rows.length}종 · ${total.toLocaleString()} tri · 평균 ${Math.round(total / rows.length).toLocaleString()} tri`);

  if (failed.length > 0) {
    console.log('\n  ! 로드 실패 — 숫자를 지어내지 않는다');
    for (const f of failed) console.log(`    ${f.assetCode}: ${f.error}`);
  }

  const jsonAt = process.argv.indexOf('--json');
  if (jsonAt !== -1 && process.argv[jsonAt + 1] !== undefined) {
    writeFileSync(process.argv[jsonAt + 1], JSON.stringify({ rows, failed }, null, 2));
    console.log(`\n  → ${process.argv[jsonAt + 1]}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
