// Unity 원본 → **Intermediate** GLB + manifest (S15P21A604-473).
//
//   node tools/assets/build-booth-assets.mjs
//
// 이 스크립트의 역할은 orchestration 이다 — 입력 해석은 source.mjs 가, 런타임 산출물은
// compiler/ 가 맡는다(S15P21A604-480 에서 갈랐다). 여기서 만드는 GLB 는 **Intermediate** 다:
// 원본 구조에 가깝고 build-time 전용이며 git·production Docker·production serving 금지.
//
// 외부 도구를 쓰지 않는다. Blender·FBX2glTF·assimp 는 이 장비에 없고, three 가 FBXLoader 와
// GLTFExporter 를 이미 갖고 있다.
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { BOOTH_ASSETS } from './booth-assets.config.mjs';
import { convert, projectRoot, roundAll, warnings } from './source.mjs';

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

const outDir = resolve(projectRoot, 'public/assets/booth');

const round = (n) => Number(n.toFixed(4));

async function main() {
  mkdirSync(outDir, { recursive: true });
  const exporter = new GLTFExporter();
  const entries = [];

  for (const source of BOOTH_ASSETS) {
    const { root, rawBounds, normalizedBounds, triangles } = convert(source);
    const glb = Buffer.from(await exporter.parseAsync(root, { binary: true }));
    const file = `${source.assetCode}.glb`;
    writeFileSync(resolve(outDir, file), glb);

    entries.push({
      assetCode: source.assetCode,
      objectType: source.objectType,
      typeDefault: source.typeDefault === true,
      url: `assets/booth/${file}`,
      bytes: glb.length,
      triangles,
      /** 정규화 후 실측 bbox(m). 런타임이 계약 AABB 와 대조해 어긋나면 드러낸다 */
      bounds: roundAll(normalizedBounds),
      source: {
        kind: source.kind,
        fbx: source.fbx ?? source.prefab,
        unitScale: source.unitScale,
        upAxis: source.upAxis,
        rawBounds: roundAll(rawBounds),
      },
    });

    const size = normalizedBounds.max.map((v, i) => round(v - normalizedBounds.min[i]));
    console.log(
      `${source.assetCode.padEnd(20)} ${String(glb.length).padStart(7)} B  tri ${String(triangles).padStart(5)}  ` +
        `size ${size.join(' × ')} m`,
    );
  }

  const manifest = {
    // 버전은 로더가 모르는 형식을 만났을 때 조용히 넘어가지 않게 하려고 둔다
    version: 1,
    generatedAt: new Date().toISOString(),
    note: 'Unity 원본에서 생성된 로컬 산출물. 벤더 에셋 재배포 확인 전까지 저장소·정적 배포에 넣지 않는다.',
    assets: entries,
  };
  writeFileSync(resolve(outDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`\nmanifest: ${resolve(outDir, 'manifest.json')} (${entries.length} assets)`);
  if (warnings.length > 0) console.log(`경고 ${warnings.length}건 — 위 목록 참조`);
}

await main();
