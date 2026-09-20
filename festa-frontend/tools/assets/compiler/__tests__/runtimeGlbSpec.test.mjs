// 런타임 GLB 가 glTF 스펙을 지키는지, 썸네일 깊이 판정이 맞는지 (S15P21A604-552).
//
// 둘 다 **조용한 결함**이었다. 로그 한 줄("Some extensions were not registered for I/O")과
// 알아볼 수 없는 썸네일만 남기고 컴파일은 성공으로 끝났다.
import { describe, expect, it } from 'vitest';
import { Document } from '@gltf-transform/core';
import { quantize } from '@gltf-transform/functions';
import { transformRuntimeDocument } from '../toolchain/gltfPipeline.mjs';
import { createIO } from '../toolchain/gltfPipeline.mjs';
import { makeCamera, renderToRaw } from '../toolchain/rasterizer.mjs';

/** 삼각형 하나짜리 최소 문서 — quantize 가 붙을 수 있는 최소 단위다 */
function triangleDocument() {
  const doc = new Document();
  const buffer = doc.createBuffer();
  const position = doc
    .createAccessor()
    .setType('VEC3')
    .setArray(new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0]))
    .setBuffer(buffer);
  const primitive = doc.createPrimitive().setAttribute('POSITION', position);
  const mesh = doc.createMesh().addPrimitive(primitive);
  const node = doc.createNode().setMesh(mesh);
  doc.createScene().addChild(node);
  return doc;
}

describe('런타임 GLB — quantize 는 확장 선언과 함께 나가야 한다', () => {
  it('KHR_mesh_quantization 이 extensionsRequired 에 들어간다', async () => {
    // 확장을 등록하지 않으면 POSITION 은 정규화된 SHORT 인데 그것을 선언하는 extension 이
    // 없는 **스펙 위반 GLB** 가 나온다. 관대한 로더는 읽어 주지만 엄격한 쪽은 거부한다
    const doc = triangleDocument();
    await doc.transform(quantize({ quantizePosition: 14 }));
    const glb = Buffer.from(await createIO().writeBinary(doc));
    const json = JSON.parse(glb.subarray(20, 20 + glb.readUInt32LE(12)).toString('utf8'));

    expect(json.extensionsRequired ?? []).toContain('KHR_mesh_quantization');
    // 선언이 실제 데이터와 맞는지도 본다 — 선언만 있고 quantize 가 안 됐으면 의미가 없다
    const accessor = json.accessors[json.meshes[0].primitives[0].attributes.POSITION];
    expect(accessor.componentType).not.toBe(5126); // FLOAT 가 아니다
    expect(accessor.normalized).toBe(true);
  });
});

describe('런타임 GLB — 텍스처가 붙을 UV 가 남아야 한다', () => {
  /** 삼각형 하나 + TEXCOORD_0. 재질에는 아직 텍스처가 없다 — 실제 파이프라인이 그렇다 */
  function uvDocument() {
    const doc = new Document();
    const buffer = doc.createBuffer();
    const position = doc.createAccessor().setType('VEC3')
      .setArray(new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0])).setBuffer(buffer);
    const uv = doc.createAccessor().setType('VEC2')
      .setArray(new Float32Array([0, 0, 1, 0, 0, 1])).setBuffer(buffer);
    const material = doc.createMaterial().setBaseColorFactor([1, 1, 1, 1]);
    const primitive = doc.createPrimitive()
      .setAttribute('POSITION', position).setAttribute('TEXCOORD_0', uv).setMaterial(material);
    const node = doc.createNode().setMesh(doc.createMesh().addPrimitive(primitive));
    doc.createScene().addChild(node);
    return doc;
  }

  it('transform 을 통과해도 TEXCOORD_0 이 남는다', async () => {
    // prune 의 기본값(keepAttributes:false)은 "어느 재질도 안 쓰는" 정점 속성을 지운다.
    // 이 파이프라인은 텍스처를 **prune 다음에** 굽기 때문에, 기본값이면 UV 가 통째로
    // 사라지고 GLB 는 이미지·연결을 다 갖춘 채 **샘플링만 안 되는** 상태로 나온다(LJH T-80)
    const doc = uvDocument();
    await transformRuntimeDocument(doc, { simplifyRatio: null, simplifyError: 0.01 });
    const kept = doc.getRoot().listMeshes()
      .flatMap((m) => m.listPrimitives())
      .filter((p) => p.getAttribute('TEXCOORD_0') !== null);
    expect(kept.length).toBeGreaterThan(0);
  });
});

describe('rasterizer 깊이 — 가까운 면이 이긴다', () => {
  /** z 평면에 놓인 삼각형 하나 */
  const triAt = (z, n) => ({
    positions: new Float32Array([-9, -9, z, 9, -9, z, 0, 9, z]),
    normals: new Float32Array([...n, ...n, ...n]),
  });

  const camera = makeCamera({ direction: [0, 0, 1], target: [0, 0, 0], radius: 10, size: 16 });
  /** 칠해진 픽셀의 (index, r, g, b) 목록 — 어느 픽셀이 칠해지는지는 이 테스트의 관심이 아니다 */
  const painted = ({ rgba }) => {
    const out = [];
    for (let i = 0; i < rgba.length; i += 4) {
      if (rgba[i + 3] !== 0) out.push([i, rgba[i], rgba[i + 1], rgba[i + 2]].join(':'));
    }
    return out;
  };

  it('뒤 면을 나중에 그려도 앞 면이 남는다', () => {
    // 카메라는 +Z 쪽에 있다 — z=+5 가 앞, z=-5 가 뒤다. 깊이 부호가 뒤집혀 있으면
    // 뒤 면이 이겨서 모델이 속을 뒤집은 파편 덩어리로 그려진다(실제로 그랬다)
    // 앞뒤에 **다른 노멀**을 준다. 색이 같으면 어느 쪽이 살아남았는지 구분할 수 없어
    // 테스트가 통과해도 아무것도 보장하지 못한다 — 음영으로 갈라 놓는다
    const near = triAt(5, [0, 0, 1]);
    const far = triAt(-5, [0, 1, 0]);
    const merged = {
      positions: new Float32Array([...near.positions, ...far.positions]),
      normals: new Float32Array([...near.normals, ...far.normals]),
    };

    const RED = { baseColor: [1, 1, 1], ambient: 0.1, keyDirection: [0, 0, 1] };
    const onlyNear = painted(renderToRaw(near, camera, RED));
    const onlyFar = painted(renderToRaw(far, camera, RED));
    expect(onlyNear.length).toBeGreaterThan(0);
    // 두 면이 실제로 다르게 보이는지 먼저 확인한다 — 같으면 아래 단언이 무의미하다
    expect(onlyNear).not.toEqual(onlyFar);

    // 앞뒤가 같은 자리에 겹쳐 있으므로, 합쳐 그린 결과는 앞면만 그린 것과 완전히 같아야 한다
    expect(painted(renderToRaw(merged, camera, RED))).toEqual(onlyNear);
    // 순서를 바꿔도 같다 — 깊이로 이기는 것이지 그린 순서로 이기는 것이 아니다
    const reversed = {
      positions: new Float32Array([...far.positions, ...near.positions]),
      normals: new Float32Array([...far.normals, ...near.normals]),
    };
    expect(painted(renderToRaw(reversed, camera, RED))).toEqual(onlyNear);
  });
});
