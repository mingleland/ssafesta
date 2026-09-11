// Facade Ground Truth — Compiler 를 거치지 않은 기준 (S15P21A604-552).
//
//   node tools/assets/facade-ground-truth.mjs [--out <dir>]
//
// **Compiler 산출물끼리 비교하지 않기 위한 것이다.** 지금까지 "원본" 이라 부른 것은
// `ratio 1.0` 산출물이었고, 그것도 flatten·dedup·join·weld·quantize·텍스처 재인코딩을
// 전부 거친 뒤였다. 그 기준으로는 **Compiler 자체가 무엇을 잃는지** 알 수 없다.
//
// 여기서 만드는 것:
//   ① prefab → three 씬 → GLTFExporter → GLB      (transform 0회 · quantize 0회)
//   ② 재질 → 원본 텍스처 파일 경로 매핑 JSON      (리사이즈·재인코딩 0회)
// 브라우저가 ②를 보고 원본 PNG 를 직접 붙여 렌더한다.
//
// **이 장비에 Unity Editor 가 없다**(`ProjectVersion 6000.0.78f1`, Hub 미설치). 그래서
// 이것은 "Unity 렌더" 가 아니라 **"같은 FBX·같은 텍스처를 Compiler 없이 그린 것"** 이다.
// Unity 셰이더(URP Lit)의 표현까지 재현하지는 않는다 — 그 한계를 안고 기준으로 쓴다.
import { mkdirSync, writeFileSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import { relative, resolve } from 'node:path';
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { CARNIVAL_PREFAB_ROOT, CM_TO_M, FACADE_ASSETS } from './facade-assets.config.mjs';
import { boundsOf, loadFbx, projectRoot, sharedGuidIndex } from './source.mjs';
import { loadPrefab } from './unity-prefab.mjs';

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

/** Unity `.mat` 의 텍스처 슬롯 → glTF 채널. Compiler 와 같은 대응을 쓴다 */
const SLOTS = [
  ['map', ['_BaseMap', '_MainTex']],
  ['normalMap', ['_BumpMap']],
  ['metalnessMap', ['_MetallicGlossMap', '_SpecGlossMap']],
];

function textureGuid(text, slot) {
  const m = new RegExp(`- ${slot}:\\r?\\n\\s*m_Texture: \\{fileID: (-?\\d+)(?:, guid: ([0-9a-f]{32}))?`).exec(text);
  return m === null || m[2] === undefined ? null : m[2];
}

/** `.mat` 하나가 참조하는 **원본** 텍스처 파일들 — 리사이즈도 재인코딩도 하지 않는다 */
function sourceTextures(matPath, guidIndex) {
  const text = readFileSync(matPath, 'utf8');
  const out = {};
  for (const [channel, slots] of SLOTS) {
    for (const slot of slots) {
      const guid = textureGuid(text, slot);
      if (guid === null) continue;
      const path = guidIndex.get(guid);
      if (path !== undefined) out[channel] = path.replace(/\\/g, '/');
      break;
    }
  }
  return out;
}

async function main() {
  const at = process.argv.indexOf('--out');
  const outDir = at === -1 ? resolve(projectRoot, '.generated/facade-gt') : resolve(process.argv[at + 1]);
  mkdirSync(outDir, { recursive: true });

  const guidIndex = sharedGuidIndex();
  const codes = process.argv.includes('--samples')
    ? process.argv[process.argv.indexOf('--samples') + 1].split(',')
    : FACADE_ASSETS.map((a) => a.assetCode);

  const index = [];
  for (const code of codes) {
    const source = FACADE_ASSETS.find((a) => a.assetCode === code);
    if (source === undefined) continue;

    const raw = loadPrefab(resolve(projectRoot, CARNIVAL_PREFAB_ROOT, source.prefab), {
      guidIndex,
      unitScale: CM_TO_M,
      loadFbx,
      warn: () => {},
    });
    const root = new THREE.Group();
    root.add(raw);
    const b = boundsOf(root);
    raw.position.set(-(b.min[0] + b.max[0]) / 2, -b.min[1], -(b.min[2] + b.max[2]) / 2);

    // 재질 이름 → 원본 텍스처. exporter 가 이름을 glTF material 로 실어 준다
    const materials = {};
    let triangles = 0;
    root.traverse((o) => {
      if (!o.isMesh) return;
      const pos = o.geometry.getAttribute('position');
      triangles += (o.geometry.index ? o.geometry.index.count : pos.count) / 3;
      for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
        const matPath = m?.userData?.unityMaterialPath;
        if (typeof matPath !== 'string' || materials[m.name] !== undefined) continue;
        materials[m.name] = sourceTextures(matPath, guidIndex);
      }
    });

    // **transform 을 걸지 않는다** — 이 GLB 가 기준이다
    const glb = Buffer.from(await new GLTFExporter().parseAsync(root, { binary: true }));
    writeFileSync(resolve(outDir, `${code}.glb`), glb);

    const size = boundsOf(root);
    index.push({
      assetCode: code,
      displayName: source.displayName,
      glb: `${code}.glb`,
      bytes: glb.length,
      triangles: Math.round(triangles),
      size: [size.max[0] - size.min[0], size.max[1] - size.min[1], size.max[2] - size.min[2]].map((n) =>
        Number(n.toFixed(3)),
      ),
      materials,
    });
    console.log(
      `  ${code.padEnd(32)} ${String(Math.round(triangles)).padStart(8)} tri · ${(glb.length / 1048576).toFixed(2)} MB · 재질 ${Object.keys(materials).length}`,
    );
  }

  writeFileSync(resolve(outDir, 'index.json'), JSON.stringify(index, null, 2));
  console.log(`\n  → ${outDir}`);
  console.log('  ! 이것은 Unity 렌더가 아니다 — 같은 FBX·같은 텍스처를 Compiler 없이 그린 것이다.');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
