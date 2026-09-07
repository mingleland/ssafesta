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
import { dedup, flatten, join, prune, quantize, simplify, weld } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';

export const TOOLCHAIN = {
  gltfTransform: '@gltf-transform/functions',
  simplifier: 'meshoptimizer/MeshoptSimplifier',
  imageCodec: 'sharp',
};

export function createIO() {
  return new NodeIO();
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

  await document.transform(prune());
  applied.push('prune');
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
