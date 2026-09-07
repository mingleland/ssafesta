// Emit stage — runtime asset · thumbnail · manifest (S15P21A604-480).
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { ensureDir } from './paths.mjs';

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

/**
 * authoring 흔적을 지운다.
 *
 * 런타임이 알아야 하는 것은 "이 assetCode 의 모양" 뿐이다. 원본 파일명·노드명·재질명은
 * 알 필요가 없고, 남겨 두면 런타임 산출물이 원본 구조의 사본처럼 읽힌다.
 * 최적화와 원본 보호를 위한 처리이고, 라이선스 판정은 Source Package Gate 가 한다.
 */
export function stripMetadata(object3d, assetCode) {
  let index = 0;
  object3d.traverse((o) => {
    o.name = o === object3d ? assetCode : `${assetCode}_${index}`;
    index += 1;
    o.userData = {};
    if (o.isMesh) {
      o.geometry.name = '';
      if (o.material !== undefined && !Array.isArray(o.material)) o.material.name = '';
    }
  });
  return object3d;
}

/** 런타임 재질 — 원본 재질 객체를 재사용하지 않고 서술값으로 새로 만든다 */
export function buildRuntimeMaterial(runtime) {
  return new THREE.MeshStandardMaterial({
    color: new THREE.Color(runtime.baseColor[0], runtime.baseColor[1], runtime.baseColor[2]),
    metalness: runtime.metalness,
    roughness: runtime.roughness,
    transparent: runtime.opacity < 1,
    opacity: runtime.opacity,
  });
}

export async function emitRuntimeAsset(object3d, { dir, assetCode }) {
  ensureDir(dir);
  const glb = Buffer.from(await new GLTFExporter().parseAsync(object3d, { binary: true }));
  const file = `${assetCode}.glb`;
  writeFileSync(resolve(dir, file), glb);
  return { file, bytes: glb.length };
}

/**
 * 썸네일 카메라·조명 규격. 좌측 Asset Library 와 중앙 Canvas 가 **같은 runtime asset** 에서
 * 나오게 하려는 것이라, 규격은 asset 치수에서 결정론적으로 나와야 한다.
 *
 * 실제 래스터화는 하지 않는다 — Node 에 GL 컨텍스트가 없다. 규격만 내고 렌더러가 붙으면
 * 그것을 그대로 먹인다. `rendered: false` 를 숨기지 않는다.
 */
export function planThumbnail(bounds, assetCode) {
  const size = [bounds.max[0] - bounds.min[0], bounds.max[1] - bounds.min[1], bounds.max[2] - bounds.min[2]];
  const radius = Math.hypot(size[0], size[1], size[2]) / 2;
  return {
    assetCode,
    rendered: false,
    reason: 'Node 에 GL 컨텍스트가 없다 — headless 렌더러가 붙으면 이 규격으로 굽는다',
    spec: {
      // 캔버스와 같은 고정 아이소메트릭 방향. 썸네일만 다른 각도를 쓰면 팔레트와 캔버스가 갈린다
      cameraDirection: [1, 1, 1],
      projection: 'orthographic',
      frameRadius: Number((radius * 1.15).toFixed(4)),
      target: [0, Number((size[1] / 2).toFixed(4)), 0],
      lighting: { ambient: 0.55, keyIntensity: 2.1, keyDirection: [1, 1.4, 0.8] },
      output: { format: 'webp', size: 256, background: 'transparent' },
    },
  };
}

/**
 * runtime manifest. `-473` 의 intermediate manifest 와 **다른 스키마**다 —
 * 여기에는 원본 경로·FBX 이름이 없다.
 */
export function emitRuntimeManifest(entries, { dir, emission }) {
  ensureDir(dir);
  const manifest = {
    version: 1,
    kind: 'ssafesta-runtime-asset-manifest',
    generatedAt: new Date().toISOString(),
    emission,
    assets: entries,
  };
  const path = resolve(dir, 'manifest.json');
  writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
  return path;
}
