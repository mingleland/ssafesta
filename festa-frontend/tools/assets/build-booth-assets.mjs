// Unity 원본 FBX → 웹용 GLB + manifest (S15P21A604-473).
//
//   node tools/assets/build-booth-assets.mjs
//
// 외부 도구를 쓰지 않는다. Blender·FBX2glTF·assimp 는 이 팀 장비에 없고, three 가
// FBXLoader 와 GLTFExporter 를 이미 갖고 있다. 의존성을 하나도 늘리지 않고 변환이 된다면
// 그게 가장 싼 파이프라인이다.
//
// 하는 일은 넷이다.
//   1. FBX 파싱
//   2. 축·단위 정규화 — 원본은 Z-up 에 인치다. Y-up 미터로 눕혀 세운다
//   3. 피벗 정규화 — 바닥(min.y=0) 중앙으로 옮긴다. 계약이 "원점 = 바닥 중앙"이기 때문이다
//   4. GLB 로 굽고 manifest 에 실측 bbox 를 남긴다
//
// 텍스처는 가져오지 않는다(범위 밖). 재질은 단색으로 갈아 끼운다 — 사용 텍스처 원본 합이
// ~24MB 라 그대로는 웹에 못 올리고, 이번 검증의 대상도 아니다.
//
// 산출물은 저장소에 커밋하지 않는다. 3D 에셋이 전부 벤더팩이고 웹 재배포 가부가
// 확인되지 않았다 — 확인 전에는 정적 배포도 전체 반입도 하지 않는다.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { FBXLoader } from 'three/addons/loaders/FBXLoader.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { BOOTH_ASSETS, UNITY_ASSET_ROOT, UNITY_ASSETS_ROOT, UNITY_PREFAB_ROOT } from './booth-assets.config.mjs';
import { buildGuidIndex, loadPrefab } from './unity-prefab.mjs';

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

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '../..');
const outDir = resolve(projectRoot, 'public/assets/booth');

/**
 * FBX 안의 텍스처 참조를 빈 텍스처로 받아 넘긴다.
 *
 * 텍스처는 이번 범위가 아니지만, 없다고 무시할 수는 없다 — FBXLoader 가 참조를 만나면
 * 로더를 찾아 setPath 까지 부르기 때문에 평범한 객체를 주면 파싱이 통째로 죽는다(Tablet.FBX).
 * 그래서 Loader 흉내를 제대로 내는 것을 준다.
 */
class NullTextureLoader extends THREE.Loader {
  load(_url, onLoad) {
    const texture = new THREE.Texture();
    onLoad?.(texture);
    return texture;
  }
}

function silenceTextures(manager) {
  manager.addHandler(/\.(png|jpe?g|tga|psd|bmp|dds|tif|tiff)$/i, new NullTextureLoader(manager));
}

function loadFbx(absPath) {
  const buf = readFileSync(absPath);
  const manager = new THREE.LoadingManager();
  silenceTextures(manager);
  const loader = new FBXLoader(manager);
  return loader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
}

function boundsOf(object3d) {
  object3d.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(object3d);
  return {
    min: [box.min.x, box.min.y, box.min.z],
    max: [box.max.x, box.max.y, box.max.z],
  };
}

const round = (n) => Number(n.toFixed(4));
const roundAll = (b) => ({ min: b.min.map(round), max: b.max.map(round) });

// guid 역인덱스는 한 번만 만든다 — Assets 전 트리를 훑는 일이라 두 번 할 이유가 없다
let guidIndex = null;
const warnings = [];
const warn = (message) => {
  warnings.push(message);
  console.warn(`  ! ${message}`);
};

function sourceObject(source) {
  if (source.kind === 'prefab') {
    guidIndex ??= buildGuidIndex(resolve(projectRoot, UNITY_ASSETS_ROOT));
    return loadPrefab(resolve(projectRoot, UNITY_PREFAB_ROOT, source.prefab), {
      guidIndex,
      unitScale: source.unitScale,
      loadFbx,
      warn,
    });
  }
  return loadFbx(resolve(projectRoot, UNITY_ASSET_ROOT, source.fbx));
}

function convert(source) {
  const raw = sourceObject(source);
  const rawBounds = boundsOf(raw);

  // 텍스처를 안 가져오므로 재질도 원본을 쓸 이유가 없다. 색은 런타임이 계약 표에서 준다.
  let triangles = 0;
  raw.traverse((o) => {
    if (!o.isMesh) return;
    o.material = new THREE.MeshStandardMaterial({ color: 0xd8dce6, roughness: 0.7 });
    const pos = o.geometry.getAttribute('position');
    triangles += (o.geometry.index ? o.geometry.index.count : pos.count) / 3;
  });

  // 축·단위. Z-up 원본을 x축으로 -90° 눕히면 Y-up 이 된다 — Unity 프리팹이 하는 것과 같다.
  const root = new THREE.Group();
  const inner = new THREE.Group();
  inner.add(raw);
  if (source.upAxis === 'zUp') inner.rotation.x = -Math.PI / 2;
  // prefab 은 Transform 이 이미 미터로 되어 있고 메시 스케일은 로더가 각 메시에 걸었다.
  // 여기서 또 곱하면 두 번 줄어든다 — 단일 FBX 만 여기서 단위를 맞춘다.
  if (source.kind !== 'prefab') inner.scale.setScalar(source.unitScale);
  root.add(inner);

  // 피벗 — 바닥 중앙. 계약(헌법 21조)이 원점을 바닥 중앙으로 못박고 있고, 편집기의 이동·회전이
  // 그 원점을 기준으로 돈다. 여기서 안 맞추면 회전할 때 모델이 궤도를 그린다.
  const fitted = boundsOf(root);
  const cx = (fitted.min[0] + fitted.max[0]) / 2;
  const cz = (fitted.min[2] + fitted.max[2]) / 2;
  inner.position.set(-cx, -fitted.min[1], -cz);

  return { root, rawBounds, normalizedBounds: boundsOf(root), triangles: Math.round(triangles) };
}

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
