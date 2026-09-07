// SSAFESTA Runtime Asset Compiler — stage 조립 (S15P21A604-480).
//
// 한 함수에 몰지 않는다. 단계마다 무엇을 했고 무엇을 안 했는지 리포트에 남기는 것이
// 이 파일의 절반이다 — 안 한 것을 한 것처럼 보이게 하지 않으려는 것이다.
//
//   resolve  →  normalize  →  flatten  →  optimize geometry
//            →  bake material  →  optimize texture
//            →  strip metadata  →  emit runtime asset  →  emit thumbnail  →  emit manifest
//
// resolve·normalize 는 -476·-473 이 이미 만든 것을 그대로 쓴다. 다시 짜지 않는다.
import * as THREE from 'three';
import { countTriangles, flattenAndMerge, planSimplification, quantizePositions } from './geometry.mjs';
import { planTextureOptimization, resolveMaterial } from './material.mjs';
import { buildRuntimeMaterial, emitRuntimeAsset, planThumbnail, stripMetadata } from './emit.mjs';
import { evaluate } from './licenseGate.mjs';

const round = (n) => Number(n.toFixed(4));

function boundsOf(object3d) {
  object3d.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(object3d);
  return { min: [box.min.x, box.min.y, box.min.z].map(round), max: [box.max.x, box.max.y, box.max.z].map(round) };
}

/**
 * asset 하나를 컴파일한다.
 *
 * @param {object} input
 * @param {string} input.assetCode
 * @param {string} input.objectType        계약 ObjectType — 도메인은 여기 남고 geometry 로 옮기지 않는다
 * @param {string} input.sourcePackage     License Gate 판정 단위
 * @param {THREE.Object3D} input.source    resolve+normalize 를 마친 씬 (Intermediate 와 같은 것)
 * @param {string|null} input.materialPath Unity `.mat` 절대 경로 (없으면 색만)
 * @param {Map<string,string>} input.guidIndex
 * @param {object} options
 * @param {string} options.runtimeDir
 */
export async function compileAsset(input, options) {
  const report = { assetCode: input.assetCode, stages: {} };

  // ── license gate ───────────────────────────────────────────────
  const license = evaluate(input.sourcePackage);
  report.stages.license = license;
  if (!license.compile) return { report, emitted: null };

  // ── flatten / merge ────────────────────────────────────────────
  const sourceTriangles = countTriangles(input.source);
  const { geometry, sourceMeshCount } = flattenAndMerge(input.source);
  report.stages.flatten = {
    applied: true,
    sourceMeshCount,
    // 계층·노드명이 여기서 사라진다. 런타임은 authoring 트리를 갖지 않는다
    runtimeMeshCount: 1,
  };

  // ── optimize geometry ──────────────────────────────────────────
  const runtimeTriangles = Math.round(geometry.getAttribute('position').count / 3);
  report.stages.geometry = {
    sourceTriangles,
    runtimeTriangles,
    simplify: planSimplification(runtimeTriangles),
    quantize: { applied: true, precision: 0.001, note: '편집 격자가 0.25m 라 mm 아래는 의미가 없다' },
  };
  quantizePositions(geometry);

  // ── bake material / optimize texture ───────────────────────────
  const material =
    input.materialPath === null
      ? { runtime: { baseColor: [0.85, 0.86, 0.9], opacity: 1, metalness: 0, roughness: 0.7 }, textures: [] }
      : resolveMaterial(input.materialPath, input.guidIndex);
  report.stages.material = {
    resolved: input.materialPath !== null,
    runtime: material.runtime,
    textureCount: material.textures.length,
    // bake·atlas 는 도구가 붙은 뒤다. 지금 한 것은 source resolution 까지다
    bake: { applied: false, reason: 'UV 재생성·bake 도구(Blender headless)가 아직 없다' },
  };
  report.stages.texture = planTextureOptimization(material.textures);

  // ── strip metadata + runtime object ────────────────────────────
  const mesh = new THREE.Mesh(geometry, buildRuntimeMaterial(material.runtime));
  const runtimeRoot = new THREE.Group();
  runtimeRoot.add(mesh);
  stripMetadata(runtimeRoot, input.assetCode);
  report.stages.strip = {
    applied: true,
    removed: ['original hierarchy', 'node names', 'geometry name', 'material name', 'userData'],
  };

  // ── emit ───────────────────────────────────────────────────────
  const bounds = boundsOf(runtimeRoot);
  const emitted = await emitRuntimeAsset(runtimeRoot, { dir: options.runtimeDir, assetCode: input.assetCode });
  const thumbnail = planThumbnail(bounds, input.assetCode);
  report.stages.emit = { ...emitted, emission: license.emission };
  report.stages.thumbnail = thumbnail;

  return {
    report,
    emitted: {
      assetCode: input.assetCode,
      objectType: input.objectType,
      url: `runtime/${emitted.file}`,
      bytes: emitted.bytes,
      triangles: runtimeTriangles,
      bounds,
      material: material.runtime,
      thumbnail: thumbnail.rendered ? `runtime/${input.assetCode}.webp` : null,
      emission: license.emission,
    },
  };
}

/** 리포트를 사람이 읽는 한 덩어리로 */
export function formatReport(report) {
  const g = report.stages.geometry;
  const t = report.stages.texture;
  const lines = [`■ ${report.assetCode}`];
  lines.push(`  license   ${report.stages.license.emission ?? 'compile 안 함'}${report.stages.license.reason ? ` — ${report.stages.license.reason}` : ''}`);
  if (!report.stages.license.compile) return lines.join('\n');
  lines.push(`  flatten   mesh ${report.stages.flatten.sourceMeshCount} → 1`);
  lines.push(`  geometry  tri ${g.sourceTriangles} → ${g.runtimeTriangles} · simplify ${g.simplify.applied ? '적용' : `보류(${g.simplify.reason})`}`);
  lines.push(`  material  텍스처 ${report.stages.material.textureCount}개 · bake 보류`);
  lines.push(`  texture   원본 ${t.sourceBytes} B → runtime ${t.runtimeBytes ?? '미적용'} (${t.reason})`);
  lines.push(`  emit      ${report.stages.emit.file} ${report.stages.emit.bytes} B`);
  lines.push(`  thumbnail ${report.stages.thumbnail.rendered ? '생성' : `보류(${report.stages.thumbnail.reason})`}`);
  return lines.join('\n');
}
