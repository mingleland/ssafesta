// Runtime geometry toolchain — glTF-Transform + meshoptimizer (S15P21A604-480).
//
// 도구 선정 결과다. Blender headless 는 이 장비에 없고 설치가 CI 로 이어지지 않는 반면,
// 아래 셋은 npm devDependency 로 고정되고 CLI 한 줄로 재현된다.
//
//   @gltf-transform/functions  flatten · dedup · join · weld · simplify · prune · quantize
//   meshoptimizer              simplify 의 실제 구현
//   sharp                      텍스처 리사이즈·재패킹·webp 인코딩
//
// GUI 조작은 파이프라인 정본이 아니다 — 여기 있는 것은 전부 명령으로 다시 돌릴 수 있다.
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { dedup, flatten, join, prune, quantize, simplify, weld } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';

export const TOOLCHAIN = {
  gltfTransform: '@gltf-transform/functions',
  simplifier: 'meshoptimizer/MeshoptSimplifier',
  imageCodec: 'sharp',
};

/**
 * 확장을 **반드시 등록한다.** 등록하지 않으면 `quantize()` 가 붙인
 * `KHR_mesh_quantization` 이 파일에 안 써지고(`Some extensions were not registered for I/O`),
 * `POSITION` 은 정규화된 SHORT 인데 그것을 선언하는 extension 이 없는 **스펙 위반 GLB** 가 나온다.
 *
 * 관대한 로더는 읽어 주지만 엄격한 쪽은 거부하고, 우리 자체 래스터라이저는 그 좌표를
 * float 로 읽어 썸네일을 파편 덩어리로 만들었다.
 */
export function createIO() {
  return new NodeIO().registerExtensions(ALL_EXTENSIONS);
}

/**
 * 원본 구조를 헐어 런타임 표현으로 만든다.
 *
 * 순서에 의미가 있다.
 *   flatten  부모/자식 transform 을 정점에 굽는다 — authoring 계층이 사라지는 지점
 *   dedup    같은 것을 두 번 들고 있지 않게
 *   join     draw call 을 줄인다. 재질이 같은 primitive 를 합친다
 *   weld     정점 병합. simplify 가 붙을 수 있는 topology 를 만든다
 *   simplify 삼각형 감소. **조건이 맞을 때만** 부른다
 *   prune    아무도 안 쓰는 것 제거
 *   quantize 정점 정밀도 축소
 */
export async function transformRuntimeDocument(document, { simplifyRatio, simplifyError }) {
  const applied = [];

  await document.transform(flatten());
  applied.push('flatten');
  await document.transform(dedup());
  applied.push('dedup');
  await document.transform(join({ keepNamed: false }));
  applied.push('join');
  await document.transform(weld());
  applied.push('weld');

  if (simplifyRatio !== null) {
    await MeshoptSimplifier.ready;
    await document.transform(
      simplify({ simplifier: MeshoptSimplifier, ratio: simplifyRatio, error: simplifyError }),
    );
    applied.push(`simplify(ratio=${simplifyRatio}, error=${simplifyError})`);
  }

  // `keepAttributes: true` 가 **필수**다. 기본값(false)은 "아무 재질도 안 쓰는 정점 속성" 을
  // 지우는데, 이 파이프라인은 텍스처를 **prune 다음에** 굽는다(`bakeMaterials`). 그래서 prune
  // 시점의 재질에는 텍스처가 없고, `TEXCOORD_0` 이 통째로 미사용으로 판정돼 사라진다.
  //
  // 결과가 조용하다 — GLB 안에 이미지도 있고 material.baseColorTexture 연결도 있는데 UV 가
  // 없어 **샘플링만 안 된다.** 모델이 단색으로 렌더되고, 리포트에는 "텍스처 N개 · M 바이트" 가
  // 정상으로 찍힌다(LJH T-80).
  await document.transform(prune({ keepAttributes: true }));
  applied.push('prune(keepAttributes)');
  await document.transform(quantize({ quantizePosition: 14, quantizeNormal: 10, quantizeTexcoord: 12 }));
  applied.push('quantize');

  return applied;
}

/** 문서 안의 삼각형·정점·mesh·재질·텍스처 수 — before/after 를 같은 자로 잰다 */
export function measure(document) {
  const root = document.getRoot();
  let triangles = 0;
  let vertices = 0;
  let primitives = 0;
  for (const mesh of root.listMeshes()) {
    for (const primitive of mesh.listPrimitives()) {
      primitives += 1;
      const indices = primitive.getIndices();
      const position = primitive.getAttribute('POSITION');
      vertices += position === null ? 0 : position.getCount();
      triangles += (indices === null ? (position?.getCount() ?? 0) : indices.getCount()) / 3;
    }
  }
  return {
    triangles: Math.round(triangles),
    vertices,
    meshes: root.listMeshes().length,
    primitives,
    nodes: root.listNodes().length,
    materials: root.listMaterials().length,
    textures: root.listTextures().length,
  };
}
