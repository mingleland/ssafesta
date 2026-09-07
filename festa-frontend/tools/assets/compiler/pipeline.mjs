// SSAFESTA Runtime Asset Compiler — stage 조립 (S15P21A604-480).
//
// 한 함수에 몰지 않는다. 단계마다 무엇을 했고 무엇을 안 했는지 리포트에 남기는 것이
// 이 파일의 절반이다 — 안 한 것을 한 것처럼 보이게 하지 않으려는 것이다.
//
//   license → intermediate → flatten/join/weld/(simplify)/prune/quantize
//           → material bake → texture 재패킹·압축 → metadata strip
//           → runtime GLB → thumbnail raster → manifest entry
//
// resolve·normalize 는 -476·-473 이 이미 만든 것을 그대로 쓴다. 다시 짜지 않는다.
import { writeFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { countTriangles } from './geometry.mjs';
import { planTextureOptimization, resolveMaterial } from './material.mjs';
import { evaluate } from './licenseGate.mjs';
import { ensureDir } from './paths.mjs';
import { TOOLCHAIN, createIO, measure, transformRuntimeDocument } from './toolchain/gltfPipeline.mjs';
import { DEFAULT_MAX_SIZE, describeSource, encodeForChannel } from './toolchain/textures.mjs';
import { encodeWebp, makeCamera, renderToRaw } from './toolchain/rasterizer.mjs';

// GLTFExporter 의 binary 경로가 Blob→ArrayBuffer 에 FileReader 를 쓴다. Node 에는 없다.
if (typeof globalThis.FileReader === 'undefined') {
  globalThis.FileReader = class {
    readAsArrayBuffer(blob) {
      blob.arrayBuffer().then((b) => {
        this.result = b;
        this.onloadend?.();
      });
    }
  };
}

/** 이 아래면 더 줄이지 않는다 — 얻는 것보다 외형 손실이 크다 */
export const SIMPLIFY_FLOOR_TRIANGLES = 2000;
const SIMPLIFY_RATIO = 0.6;
const SIMPLIFY_ERROR = 0.01;

const round = (n) => Number(n.toFixed(4));

/** three 씬 → 메모리상의 intermediate GLB. 파일로 떨구지 않는다 — Compiler 입력일 뿐이다 */
async function toIntermediateGlb(object3d) {
  return Buffer.from(await new GLTFExporter().parseAsync(object3d, { binary: true }));
}

/** 삼각형 수로 단순화 여부를 정한다. 지표가 목표가 되지 않게 근거를 함께 낸다 */
export function decideSimplify(triangles, floor = SIMPLIFY_FLOOR_TRIANGLES) {
  if (triangles <= floor) {
    return { ratio: null, reason: `${triangles} tri 는 예산 ${floor} 이하 — 더 줄이면 외형만 잃는다` };
  }
  return { ratio: SIMPLIFY_RATIO, reason: `${triangles} tri 는 예산 ${floor} 초과 — ratio ${SIMPLIFY_RATIO} 로 줄인다` };
}

/** 문서에서 authoring 흔적을 지운다. 런타임이 원본 어휘를 알 이유가 없다 */
function stripDocumentMetadata(document, assetCode) {
  const root = document.getRoot();
  root.setDefaultScene(root.listScenes()[0] ?? null);
  root.getAsset().generator = 'ssafesta-runtime-asset-compiler';
  root.getAsset().copyright = undefined;
  let i = 0;
  for (const list of [root.listNodes(), root.listMeshes(), root.listMaterials(), root.listTextures(), root.listScenes()]) {
    for (const item of list) {
      item.setName(i === 0 ? assetCode : `${assetCode}_${i}`);
      item.setExtras({});
      i += 1;
    }
  }
  return i;
}

const applyMatrix = (m, v) => [
  m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
  m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
  m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14],
];
const applyMatrixDirection = (m, v) => {
  const out = [
    m[0] * v[0] + m[4] * v[1] + m[8] * v[2],
    m[1] * v[0] + m[5] * v[1] + m[9] * v[2],
    m[2] * v[0] + m[6] * v[1] + m[10] * v[2],
  ];
  const len = Math.hypot(out[0], out[1], out[2]) || 1;
  return [out[0] / len, out[1] / len, out[2] / len];
};

/**
 * 렌더러가 먹을 비인덱스 world 좌표 배열.
 *
 * **node 의 world matrix 를 반드시 적용한다.** quantize() 가 정점을 정수 범위로 넣고
 * 그 배율을 node scale 로 빼 두기 때문에, accessor 값만 읽으면 좌표가 통째로 어긋난다
 * (처음에 그렇게 만들어 bbox 가 1.34 × 0.69 × 2.0 으로 나왔다).
 */
function extractRenderGeometry(document) {
  const positions = [];
  const normals = [];
  for (const node of document.getRoot().listNodes()) {
    const mesh = node.getMesh();
    if (mesh === null) continue;
    const world = node.getWorldMatrix();
    for (const primitive of mesh.listPrimitives()) {
      const position = primitive.getAttribute('POSITION');
      const normal = primitive.getAttribute('NORMAL');
      const indices = primitive.getIndices();
      const count = indices === null ? position.getCount() : indices.getCount();
      for (let i = 0; i < count; i += 1) {
        const vi = indices === null ? i : indices.getScalar(i);
        const p = applyMatrix(world, position.getElement(vi, []));
        positions.push(p[0], p[1], p[2]);
        const n = normal === null ? [0, 1, 0] : applyMatrixDirection(world, normal.getElement(vi, []));
        normals.push(n[0], n[1], n[2]);
      }
    }
  }
  return { positions: Float32Array.from(positions), normals: Float32Array.from(normals) };
}

function boundsOfGeometry(positions) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < positions.length; i += 3) {
    for (let k = 0; k < 3; k += 1) {
      min[k] = Math.min(min[k], positions[i + k]);
      max[k] = Math.max(max[k], positions[i + k]);
    }
  }
  return { min: min.map(round), max: max.map(round) };
}

/** 런타임 재질을 문서에 심는다. 원본 재질 객체를 재사용하지 않는다 */
async function bakeMaterial(document, runtime, textures, assetCode, options) {
  const root = document.getRoot();
  const material = root.listMaterials()[0] ?? document.createMaterial();
  material
    .setBaseColorFactor([runtime.baseColor[0], runtime.baseColor[1], runtime.baseColor[2], runtime.opacity])
    .setMetallicFactor(runtime.metalness)
    .setRoughnessFactor(runtime.roughness)
    .setDoubleSided(false);

  const encoded = [];
  for (const texture of textures) {
    if (!texture.resolved) continue;
    const source = await describeSource(texture.sourcePath);
    const result = await encodeForChannel(texture.channel, texture.sourcePath, { maxSize: options.maxTextureSize });
    const buffer = result.buffer ?? result;
    const image = document
      .createTexture(`${assetCode}_${texture.channel}`)
      .setImage(buffer)
      .setMimeType('image/webp');
    if (texture.channel === 'baseColor') material.setBaseColorTexture(image);
    else if (texture.channel === 'normal') material.setNormalTexture(image);
    else material.setMetallicRoughnessTexture(image);
    encoded.push({
      channel: texture.channel,
      sourceBytes: texture.sourceBytes,
      sourceSize: [source.width, source.height],
      runtimeBytes: buffer.length,
      runtimeSize: [result.width ?? null, result.height ?? null],
      runtimeFormat: 'image/webp',
      repacked: result.repacked ?? null,
    });
  }
  // 다른 재질이 남아 있으면 첫 재질로 몰아 준다 — 런타임은 재질 하나면 된다
  for (const primitive of root.listMeshes().flatMap((m) => m.listPrimitives())) primitive.setMaterial(material);
  for (const other of root.listMaterials()) if (other !== material) other.dispose();
  return encoded;
}

/**
 * asset 하나를 컴파일한다.
 */
export async function compileAsset(input, options) {
  const maxTextureSize = options.maxTextureSize ?? DEFAULT_MAX_SIZE;
  const report = { assetCode: input.assetCode, toolchain: TOOLCHAIN, stages: {} };

  // ── license gate ───────────────────────────────────────────────
  const license = evaluate(input.sourcePackage);
  report.stages.license = license;
  if (!license.compile) return { report, emitted: null };

  // ── intermediate ───────────────────────────────────────────────
  const sourceTriangles = countTriangles(input.source);
  const intermediate = await toIntermediateGlb(input.source);
  const io = createIO();
  const document = await io.readBinary(intermediate);
  const before = measure(document);
  report.stages.intermediate = { bytes: intermediate.length, ...before, sourceTriangles };

  // ── geometry ───────────────────────────────────────────────────
  const decision = decideSimplify(before.triangles);
  const applied = await transformRuntimeDocument(document, {
    simplifyRatio: decision.ratio,
    simplifyError: SIMPLIFY_ERROR,
  });
  const after = measure(document);
  report.stages.geometry = { applied, decision, before, after };

  // ── material / texture ─────────────────────────────────────────
  const material =
    input.materialPath === null
      ? { runtime: { baseColor: [0.85, 0.86, 0.9], opacity: 1, metalness: 0, roughness: 0.7 }, textures: [] }
      : resolveMaterial(input.materialPath, input.guidIndex);
  const encoded = await bakeMaterial(document, material.runtime, material.textures, input.assetCode, { maxTextureSize });
  const sourceBytes = planTextureOptimization(material.textures).sourceBytes;
  const runtimeBytes = encoded.reduce((sum, t) => sum + t.runtimeBytes, 0);
  report.stages.material = { runtime: material.runtime, textures: encoded };
  report.stages.texture = {
    applied: encoded.length > 0,
    sourceBytes,
    runtimeBytes,
    ratio: sourceBytes > 0 ? Number((runtimeBytes / sourceBytes).toFixed(4)) : null,
    maxSize: maxTextureSize,
  };

  // ── metadata strip ─────────────────────────────────────────────
  const renamed = stripDocumentMetadata(document, input.assetCode);
  report.stages.strip = {
    applied: true,
    renamedObjects: renamed,
    removed: ['original hierarchy', 'node/mesh/material/texture names', 'extras', 'generator/copyright'],
  };

  // ── emit runtime asset ─────────────────────────────────────────
  ensureDir(options.runtimeDir);
  const glb = Buffer.from(await io.writeBinary(document));
  const file = `${input.assetCode}.glb`;
  writeFileSync(resolvePath(options.runtimeDir, file), glb);

  const geometry = extractRenderGeometry(document);
  const bounds = boundsOfGeometry(geometry.positions);
  report.stages.emit = { file, bytes: glb.length, emission: license.emission, bounds };

  // ── thumbnail ──────────────────────────────────────────────────
  const size = [bounds.max[0] - bounds.min[0], bounds.max[1] - bounds.min[1], bounds.max[2] - bounds.min[2]];
  const camera = makeCamera({
    target: [0, size[1] / 2, 0],
    radius: (Math.hypot(size[0], size[1], size[2]) / 2) * 1.15,
    size: options.thumbnailSize ?? 256,
  });
  const thumbnail = await encodeWebp(renderToRaw(geometry, camera, { baseColor: material.runtime.baseColor }));
  const thumbFile = `${input.assetCode}.webp`;
  writeFileSync(resolvePath(options.runtimeDir, thumbFile), thumbnail);
  report.stages.thumbnail = { rendered: true, file: thumbFile, bytes: thumbnail.length, size: camera.size };

  return {
    report,
    geometry,
    emitted: {
      assetCode: input.assetCode,
      objectType: input.objectType,
      // 런타임 선택 규칙은 -473 과 같다 — 타입 기본은 선언으로만 정한다(T-148)
      typeDefault: input.typeDefault === true,
      url: `${input.assetCode}.glb`,
      thumbnail: thumbFile,
      bytes: glb.length,
      triangles: after.triangles,
      bounds,
      material: material.runtime,
      emission: license.emission,
    },
  };
}

/** 리포트를 사람이 읽는 한 덩어리로 */
export function formatReport(report) {
  const s = report.stages;
  const lines = [`■ ${report.assetCode}`];
  lines.push(`  license   ${s.license.emission ?? 'compile 안 함'}${s.license.reason ? ` — ${s.license.reason}` : ''}`);
  if (!s.license.compile) return lines.join('\n');
  lines.push(`  intermed  ${s.intermediate.bytes} B · tri ${s.intermediate.triangles} · mesh ${s.intermediate.meshes} · node ${s.intermediate.nodes}`);
  lines.push(`  geometry  ${s.geometry.applied.join(' → ')}`);
  lines.push(`            tri ${s.geometry.before.triangles} → ${s.geometry.after.triangles} · vert ${s.geometry.before.vertices} → ${s.geometry.after.vertices} · mesh ${s.geometry.before.meshes} → ${s.geometry.after.meshes} · node ${s.geometry.before.nodes} → ${s.geometry.after.nodes}`);
  lines.push(`            simplify ${s.geometry.decision.ratio === null ? '보류' : `ratio ${s.geometry.decision.ratio}`} (${s.geometry.decision.reason})`);
  for (const t of s.material.textures) {
    lines.push(`  texture   ${t.channel.padEnd(18)} ${t.sourceBytes} B ${t.sourceSize.join('x')} → ${t.runtimeBytes} B ${t.runtimeSize.join('x')} webp${t.repacked ? ` · ${t.repacked}` : ''}`);
  }
  if (s.texture.applied) lines.push(`            합계 ${s.texture.sourceBytes} → ${s.texture.runtimeBytes} B (${(s.texture.ratio * 100).toFixed(2)}%)`);
  lines.push(`  strip     ${s.strip.renamedObjects}개 이름/extras 제거`);
  lines.push(`  emit      ${s.emit.file} ${s.emit.bytes} B · bbox ${(s.emit.bounds.max[0] - s.emit.bounds.min[0]).toFixed(4)} × ${(s.emit.bounds.max[1] - s.emit.bounds.min[1]).toFixed(4)} × ${(s.emit.bounds.max[2] - s.emit.bounds.min[2]).toFixed(4)}`);
  lines.push(`  thumbnail ${s.thumbnail.file} ${s.thumbnail.bytes} B (${s.thumbnail.size}px)`);
  return lines.join('\n');
}
