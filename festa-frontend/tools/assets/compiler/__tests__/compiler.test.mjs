// Runtime Asset Compiler 판정 로직 회귀 (S15P21A604-480).
//
// 여기서 잠그는 것은 "무엇을 만들었나" 가 아니라 **판단**이다 — 컴파일해도 되는가,
// 더 줄여도 되는가, Unity 재질을 런타임 어휘로 어떻게 옮기는가. 틀리면 조용히 잘못된
// 산출물이 나오는 것들만 골랐다.
import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { EMISSION, POLICY, canEmitProduction, evaluate } from '../licenseGate.mjs';
import { SIMPLIFY_FLOOR_TRIANGLES, countTriangles, flattenAndMerge, planSimplification, quantizePositions } from '../geometry.mjs';
import { parseMaterial, planTextureOptimization } from '../material.mjs';
import { planThumbnail, stripMetadata } from '../emit.mjs';

const packs = (policy) => ({ packages: { Vendor: { runtimeCompilePolicy: policy } } });

describe('License Gate — 모르는 팩은 통과시키지 않는다', () => {
  it('선언되지 않은 package 는 compile 하지 않는다', () => {
    const result = evaluate('없는팩', packs(POLICY.ALLOW_RUNTIME_COMPILE));
    expect(result.compile).toBe(false);
    expect(result.reason).toContain('없는팩');
  });

  it('REVIEW_REQUIRED 는 compile 은 하되 로컬 Spike 산출물까지다', () => {
    const result = evaluate('Vendor', packs(POLICY.REVIEW_REQUIRED));
    expect(result.compile).toBe(true);
    expect(result.emission).toBe(EMISSION.LOCAL_SPIKE_ONLY);
    expect(canEmitProduction(result)).toBe(false);
  });

  it('ALLOW 만 배포 후보가 된다', () => {
    expect(canEmitProduction(evaluate('Vendor', packs(POLICY.ALLOW_RUNTIME_COMPILE)))).toBe(true);
  });

  it('BLOCK 은 compile 자체를 하지 않는다', () => {
    expect(evaluate('Vendor', packs(POLICY.BLOCK)).compile).toBe(false);
  });

  it('모르는 정책 문자열도 통과시키지 않는다 — 오타가 허용으로 읽히면 안 된다', () => {
    expect(evaluate('Vendor', packs('allow')).compile).toBe(false);
  });
});

describe('Geometry — 줄이는 것이 목적이 아니다', () => {
  it('예산 이하면 더 줄이지 않는다 — 286 tri 키오스크가 여기 들어온다', () => {
    const plan = planSimplification(286);
    expect(plan.applied).toBe(false);
    expect(plan.pendingTool).toBeUndefined();
  });

  it('예산 초과는 대상이지만 도구가 없다고 밝힌다 — 안 한 것을 한 것처럼 쓰지 않는다', () => {
    const plan = planSimplification(SIMPLIFY_FLOOR_TRIANGLES + 1);
    expect(plan.applied).toBe(false);
    expect(plan.pendingTool).toBeTruthy();
  });

  it('계층을 없애고 world transform 을 정점에 굽는다', () => {
    const root = new THREE.Group();
    const child = new THREE.Group();
    child.position.set(1, 2, 3);
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshStandardMaterial());
    child.add(mesh);
    root.add(child);

    const { geometry, sourceMeshCount } = flattenAndMerge(root);
    expect(sourceMeshCount).toBe(1);
    // 부모 transform 을 버려도 위치가 유지된다
    geometry.computeBoundingBox();
    expect(geometry.boundingBox.min.y).toBeCloseTo(1.5, 5);
    expect(geometry.boundingBox.max.y).toBeCloseTo(2.5, 5);
  });

  it('여러 mesh 가 하나로 합쳐진다 — 런타임은 authoring 트리를 갖지 않는다', () => {
    const root = new THREE.Group();
    for (let i = 0; i < 3; i += 1) {
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshStandardMaterial());
      mesh.position.x = i * 2;
      root.add(mesh);
    }
    const { geometry, sourceMeshCount } = flattenAndMerge(root);
    expect(sourceMeshCount).toBe(3);
    expect(countTriangles(new THREE.Mesh(geometry))).toBe(36); // 박스 12 × 3
  });

  it('정점을 mm 로 맞춘다 — 편집 격자가 0.25m 라 그 아래는 의미가 없다', () => {
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array([0.12345678, 0, 0]), 3));
    quantizePositions(geometry);
    expect(geometry.getAttribute('position').array[0]).toBeCloseTo(0.123, 6);
  });
});

const MAT = `
    m_Colors:
    - _BaseColor: {r: 0.5, g: 0.25, b: 0.125, a: 1}
    m_Floats:
    - _Metallic: 0.25
    - _Smoothness: 0.8
    m_TexEnvs:
    - _BumpMap:
        m_Texture: {fileID: 2800000, guid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, type: 3}
    - _MainTex:
        m_Texture: {fileID: 0}
`;

describe('Material — Unity 어휘를 런타임 어휘로', () => {
  it('smoothness 를 roughness 로 뒤집는다 — 이 반전이 두 곳에 있으면 안 된다', () => {
    const result = parseMaterial(MAT, new Map());
    expect(result.runtime.roughness).toBeCloseTo(0.2, 6);
    expect(result.runtime.metalness).toBeCloseTo(0.25, 6);
  });

  it('base color 를 그대로 옮긴다', () => {
    expect(parseMaterial(MAT, new Map()).runtime.baseColor).toEqual([0.5, 0.25, 0.125]);
  });

  it('참조가 비어 있는 슬롯은 텍스처로 세지 않는다', () => {
    const result = parseMaterial(MAT, new Map());
    expect(result.textures.map((t) => t.channel)).toEqual(['normal']);
  });

  it('guid 를 못 찾으면 resolved=false 로 남긴다 — 조용히 빠지지 않는다', () => {
    expect(parseMaterial(MAT, new Map()).textures[0].resolved).toBe(false);
  });

  it('텍스처가 없으면 이유를 그렇게 적는다', () => {
    expect(planTextureOptimization([]).pendingTool).toBeNull();
  });

  it('텍스처가 있으면 원본 바이트를 합치고 미적용임을 밝힌다', () => {
    const plan = planTextureOptimization([{ sourceBytes: 100 }, { sourceBytes: 200 }]);
    expect(plan.sourceBytes).toBe(300);
    expect(plan.runtimeBytes).toBeNull();
    expect(plan.applied).toBe(false);
  });
});

describe('Emit — authoring 흔적을 남기지 않는다', () => {
  it('노드·geometry·재질 이름과 userData 가 사라진다', () => {
    const root = new THREE.Group();
    root.name = 'SurveyKiosk';
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshStandardMaterial());
    mesh.name = 'Counter01Top';
    mesh.geometry.name = 'Counter01Top_mesh';
    mesh.material.name = 'PlasticWhite';
    mesh.userData = { unityPath: 'Assets/…' };
    root.add(mesh);

    stripMetadata(root, 'SURVEY_KIOSK_DEFAULT');

    expect(root.name).toBe('SURVEY_KIOSK_DEFAULT');
    expect(mesh.name).not.toContain('Counter01');
    expect(mesh.geometry.name).toBe('');
    expect(mesh.material.name).toBe('');
    expect(mesh.userData).toEqual({});
  });

  it('썸네일 규격이 치수에서 결정론적으로 나온다 — 캔버스와 팔레트가 같은 소스를 봐야 한다', () => {
    const spec = planThumbnail({ min: [-0.31, 0, -0.16], max: [0.31, 0.93, 0.16] }, 'X').spec;
    expect(spec.cameraDirection).toEqual([1, 1, 1]);
    expect(spec.target[1]).toBeCloseTo(0.465, 4);
    expect(spec.frameRadius).toBeGreaterThan(0);
  });

  it('아직 굽지 않았다는 사실을 숨기지 않는다', () => {
    const thumb = planThumbnail({ min: [0, 0, 0], max: [1, 1, 1] }, 'X');
    expect(thumb.rendered).toBe(false);
    expect(thumb.reason).toBeTruthy();
  });
});
