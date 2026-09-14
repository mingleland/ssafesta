// Runtime Asset parity 수치 산출 — 눈으로 볼 수 없는 부분을 근거로 만든다 (S15P21A604-480).
//
// 시각 판정(8각도 실루엣·재질 인상)은 브라우저에서 사람이 본다. 여기서 내는 것은 그 옆에
// 나란히 놓을 **수치**다 — 계약 AABB 대비 축별 오차, Unity `.mat` 값 대 런타임 material,
// 원본 텍스처 대 runtime webp, tri·바이트.
import { readFileSync, statSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { UNITY_ASSETS_ROOT, BOOTH_ASSETS } from './booth-assets.config.mjs';
import { resolveMaterial } from './compiler/material.mjs';
import { INTERMEDIATE_DIR, RUNTIME_DIR } from './compiler/paths.mjs';
import { sharedGuidIndex, projectRoot } from './source.mjs';

const CONTRACT = {
  SURVEY_KIOSK: { min: [-0.31, 0, -0.16], max: [0.31, 0.93, 0.16] },
  FURNITURE: { min: [-0.61, 0, -0.86], max: [0.89, 0.75, 0.86] },
  DECORATION: { min: [-0.3, 0, -0.3], max: [0.3, 1.61, 0.3] },
};

const mm = (m) => `${(m * 1000).toFixed(1)} mm`;
const size = (b) => [0, 1, 2].map((i) => b.max[i] - b.min[i]);

function glbSummary(path) {
  const buf = readFileSync(path);
  const json = JSON.parse(buf.subarray(20, 20 + buf.readUInt32LE(12)).toString('utf8'));
  const mats = (json.materials ?? []).map((m) => ({
    base: m.pbrMetallicRoughness?.baseColorTexture !== undefined,
    mr: m.pbrMetallicRoughness?.metallicRoughnessTexture !== undefined,
    normal: m.normalTexture !== undefined,
    factors: m.pbrMetallicRoughness?.baseColorFactor,
    metallic: m.pbrMetallicRoughness?.metallicFactor,
    roughness: m.pbrMetallicRoughness?.roughnessFactor,
  }));
  return { bytes: buf.length, images: (json.images ?? []).length, materials: mats };
}

const inter = JSON.parse(readFileSync(resolve(INTERMEDIATE_DIR, 'manifest.json'), 'utf8'));
const runtime = JSON.parse(readFileSync(resolve(RUNTIME_DIR, 'manifest.json'), 'utf8'));
const guidIndex = sharedGuidIndex();

for (const entry of runtime.assets) {
  const src = BOOTH_ASSETS.find((a) => a.assetCode === entry.assetCode);
  const interEntry = inter.assets.find((a) => a.assetCode === entry.assetCode);
  const contract = CONTRACT[entry.objectType];
  const glbPath = resolve(RUNTIME_DIR, entry.url);

  console.log(`\n■ ${entry.assetCode}  (${entry.objectType})`);
  console.log(`  원본            ${src.kind === 'prefab' ? src.prefab : src.fbx}  · package ${src.sourcePackage}`);

  const rs = size(entry.bounds);
  const cs = size(contract);
  console.log(`  실측 크기       ${rs.map((v) => v.toFixed(4)).join(' × ')} m`);
  console.log(`  계약 AABB       ${cs.map((v) => v.toFixed(4)).join(' × ')} m`);
  console.log(`  축별 오차       X ${mm(Math.abs(rs[0] - cs[0]))} · Y ${mm(Math.abs(rs[1] - cs[1]))} · Z ${mm(Math.abs(rs[2] - cs[2]))}`);
  if (entry.typeDefault === false) {
    console.log(`  ! typeDefault=false — 타입 기본이 아닌 assetCode 라 계약 AABB 와 다른 것이 정상이다`);
  }

  console.log(`  삼각형          intermediate ${interEntry.triangles} → runtime ${entry.triangles}`);
  const glb = glbSummary(glbPath);
  console.log(`  GLB             ${interEntry.bytes} → ${glb.bytes} B  (${((glb.bytes / interEntry.bytes) * 100).toFixed(1)}%)`);
  console.log(`  텍스처(embed)   images ${glb.images}`);
  for (const m of glb.materials) {
    console.log(`    material      baseColor ${m.base ? 'O' : '-'} · normal ${m.normal ? 'O' : '-'} · metallicRoughness ${m.mr ? 'O' : '-'}`);
    console.log(`                  factors metallic ${m.metallic ?? '(기본 1)'} · roughness ${m.roughness ?? '(기본 1)'}`);
  }

  if (src.material !== undefined) {
    const matPath = resolve(projectRoot, UNITY_ASSETS_ROOT, src.material);
    const mat = resolveMaterial(matPath, guidIndex);
    console.log(`  Unity .mat      ${src.material.split('/').pop()}`);
    console.log(`                  baseColor [${mat.runtime.baseColor.map((v) => v.toFixed(3)).join(', ')}] · metalness ${mat.runtime.metalness} · roughness ${mat.runtime.roughness.toFixed(3)}`);
    console.log(`  manifest        baseColor [${entry.material.baseColor.map((v) => v.toFixed(3)).join(', ')}] · metalness ${entry.material.metalness} · roughness ${entry.material.roughness.toFixed(3)}`);
    for (const t of mat.textures) {
      const runtimePath = resolve(projectRoot, '.generated/runtime', `${entry.assetCode}.webp`);
      const thumb = existsSync(runtimePath) ? statSync(runtimePath).size : null;
      console.log(`    ${t.channel.padEnd(18)} 원본 ${t.sourceBytes ?? '(못 찾음)'} B  ${t.sourcePath ? t.sourcePath.split(/[\\/]/).pop() : ''}`);
      void thumb;
    }
  }
  const thumbPath = resolve(projectRoot, '.generated/runtime', entry.thumbnail ?? '');
  if (entry.thumbnail && existsSync(thumbPath)) {
    console.log(`  thumbnail       ${entry.thumbnail} ${statSync(thumbPath).size} B`);
  }
}
