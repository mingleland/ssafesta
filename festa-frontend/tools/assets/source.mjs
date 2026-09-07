// Unity source 해석 — Prefab/FBX 를 three 씬으로 (S15P21A604-473 · -476 에서 만든 것).
//
// 이 파일은 **입력 해석만** 한다. 산출물을 어디에 어떤 형태로 낼지는 소비처가 정한다 —
// intermediate GLB(build-booth-assets)와 runtime asset(compiler)이 같은 입력을 쓰기 때문이다.
// S15P21A604-480 에서 build-booth-assets.mjs 로부터 옮겼다(해석 동작은 그대로다).
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { FBXLoader } from 'three/addons/loaders/FBXLoader.js';
import { UNITY_ASSET_ROOT, UNITY_ASSETS_ROOT, UNITY_PREFAB_ROOT } from './booth-assets.config.mjs';
import { buildGuidIndex, loadPrefab } from './unity-prefab.mjs';


const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '../..');

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

export function silenceTextures(manager) {
  manager.addHandler(/\.(png|jpe?g|tga|psd|bmp|dds|tif|tiff)$/i, new NullTextureLoader(manager));
}

export function loadFbx(absPath) {
  const buf = readFileSync(absPath);
  const manager = new THREE.LoadingManager();
  silenceTextures(manager);
  const loader = new FBXLoader(manager);
  return loader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
}

export function boundsOf(object3d) {
  object3d.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(object3d);
  return {
    min: [box.min.x, box.min.y, box.min.z],
    max: [box.max.x, box.max.y, box.max.z],
  };
}

export const round = (n) => Number(n.toFixed(4));
export const roundAll = (b) => ({ min: b.min.map(round), max: b.max.map(round) });

// guid 역인덱스는 한 번만 만든다 — Assets 전 트리를 훑는 일이라 두 번 할 이유가 없다
let guidIndex = null;
export const warnings = [];
export const warn = (message) => {
  warnings.push(message);
  console.warn(`  ! ${message}`);
};

export function sourceObject(source) {
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

export function convert(source) {
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


/** guid 역인덱스는 전 트리 스캔이라 한 번만 만들고 여러 stage 가 나눠 쓴다 */
export function sharedGuidIndex() {
  guidIndex ??= buildGuidIndex(resolve(projectRoot, UNITY_ASSETS_ROOT));
  return guidIndex;
}

export { projectRoot };
