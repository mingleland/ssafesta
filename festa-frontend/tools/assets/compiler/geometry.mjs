// Geometry stage — flatten / merge / optimize (S15P21A604-480).
//
// 목표는 삼각형 수를 일정 비율로 깎는 것이 **아니다.** 그건 지표를 목표로 착각한 것이고,
// 이미 286 tri 인 키오스크를 더 깎으면 얻는 것 없이 외형만 무너진다.
//
//   성공 기준   외형 근사 유지 + 필요한 최소 geometry
//   실패 기준   triangle 은 줄었는데 알아볼 수 없게 됐다
//
// 그래서 각 단계는 "해도 되는가" 를 먼저 판단하고, 판단 근거를 리포트에 남긴다.
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

/**
 * 이 크기 아래면 추가 단순화를 하지 않는다.
 * SURVEY_KIOSK(286) · DECORATION(240) 이 여기 들어온다 — 웹 런타임에서 이미 충분히 가볍다.
 */
export const SIMPLIFY_FLOOR_TRIANGLES = 2000;

export function countTriangles(object3d) {
  let total = 0;
  object3d.traverse((o) => {
    if (!o.isMesh) return;
    const position = o.geometry.getAttribute('position');
    total += (o.geometry.index ? o.geometry.index.count : position.count) / 3;
  });
  return Math.round(total);
}

/**
 * 계층을 없애고 mesh 를 하나로 합친다.
 *
 * 이 단계가 곧 "원본 hierarchy·node naming 을 런타임이 갖지 않는다" 를 만든다.
 * Unity 의 GameObject 트리는 authoring 구조지 런타임이 알아야 할 것이 아니다.
 *
 * 합치기 전에 각 mesh 의 world matrix 를 정점에 굽는다 — 그래야 부모 transform 을 버려도
 * 위치가 유지된다.
 */
export function flattenAndMerge(object3d) {
  object3d.updateMatrixWorld(true);
  const geometries = [];
  object3d.traverse((o) => {
    if (!o.isMesh) return;
    const baked = o.geometry.clone();
    baked.applyMatrix4(o.matrixWorld);
    // 합치려면 attribute 구성이 같아야 한다. 런타임에 필요한 것만 남긴다
    for (const name of Object.keys(baked.attributes)) {
      if (name !== 'position' && name !== 'normal' && name !== 'uv') baked.deleteAttribute(name);
    }
    if (baked.getAttribute('normal') === undefined) baked.computeVertexNormals();
    if (baked.getAttribute('uv') === undefined) {
      const count = baked.getAttribute('position').count;
      baked.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(count * 2), 2));
    }
    baked.morphAttributes = {};
    // 합치기 전에 인덱스를 푼다 — 이미 non-indexed 면 그대로 둔다(three 가 경고를 낸다)
    geometries.push(baked.index === null ? baked : baked.toNonIndexed());
  });
  if (geometries.length === 0) throw new Error('mesh 가 하나도 없다 — 합칠 것이 없다');
  const merged = geometries.length === 1 ? geometries[0] : mergeGeometries(geometries, false);
  if (merged === null) throw new Error('geometry 합치기에 실패했다 — attribute 구성이 서로 다르다');
  merged.computeVertexNormals();
  merged.computeBoundingBox();
  return { geometry: merged, sourceMeshCount: geometries.length };
}

/**
 * 더 줄일지 판단한다. 실제 remesh/simplify 도구(Blender·meshoptimizer)는 아직 없으므로
 * **이 단계는 판단과 근거만 낸다.** 도구가 붙으면 여기서 호출한다.
 *
 * 지금 없는 것을 있는 것처럼 쓰지 않는다 — `applied: false` 와 이유를 그대로 돌려준다.
 */
export function planSimplification(triangles, budget = SIMPLIFY_FLOOR_TRIANGLES) {
  if (triangles <= budget) {
    return {
      applied: false,
      reason: `${triangles} tri 는 예산 ${budget} 이하라 더 줄이지 않는다 — 이득보다 외형 손실이 크다`,
    };
  }
  return {
    applied: false,
    reason: `${triangles} tri 는 예산 ${budget} 초과 — 단순화 대상이지만 remesh/simplify 도구가 아직 없다`,
    pendingTool: 'meshoptimizer 또는 Blender headless',
  };
}

/**
 * 런타임 정점 정밀도. 미터 단위 좌표를 mm 로 반올림한다 —
 * 편집기의 최소 격자가 0.25 m 라 mm 아래는 화면에서도 계약에서도 의미가 없다.
 * quantization 도구가 붙기 전의 값싼 첫 단계다.
 */
export function quantizePositions(geometry, precision = 0.001) {
  const position = geometry.getAttribute('position');
  const array = position.array;
  for (let i = 0; i < array.length; i += 1) {
    array[i] = Math.round(array[i] / precision) * precision;
  }
  position.needsUpdate = true;
  geometry.computeBoundingBox();
  return geometry;
}
