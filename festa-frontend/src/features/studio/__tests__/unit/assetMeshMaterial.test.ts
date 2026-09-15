// 실물 GLB 의 재질을 편집기가 덮지 않는지 (S15P21A604-789).
//
// 예전 규칙은 "맵을 하나라도 든 재질은 두고, 맵이 없는 재질만 계약 색으로 채운다" 였다.
// 축이 틀렸다 — Runtime Asset Compiler 는 원본 .mat 의 _BaseColor 를 항상 baseColorFactor 로
// 옮기므로 **맵이 없어도 색은 있다**. 벤더 원본에는 albedo 맵 없이 normal·metallicRoughness 만
// 든 재질이 흔해서(PlasticWhite.mat 의 _BaseMap·_MainTex 가 둘 다 fileID 0), 옛 규칙은 한
// 모델 안에서 어떤 면은 authored 색, 어떤 면은 타입 색이 되게 만들었다.
//
// 그래서 여기서 재는 것은 하나다: 자산이 들고 온 재질이 그대로 나가는가.
import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { prepareInstance } from '../../ui/canvas/AssetMesh';

function scene(): { group: THREE.Group; materials: THREE.MeshStandardMaterial[] } {
  const group = new THREE.Group();
  // 컴파일된 자산의 두 모습 — 맵을 든 재질과 factor 만 든 재질이 한 모델에 섞여 있다
  const textured = new THREE.MeshStandardMaterial({ color: '#112233' });
  textured.metalnessMap = new THREE.Texture();
  const factorOnly = new THREE.MeshStandardMaterial({ color: '#d9dbe6' });
  for (const material of [textured, factorOnly]) {
    group.add(new THREE.Mesh(new THREE.BoxGeometry(), material));
  }
  return { group, materials: [textured, factorOnly] };
}

describe('prepareInstance — 자산 재질을 편집기가 덮지 않는다', () => {
  it('맵이 없는 재질도 그대로 둔다 — 계약 색으로 갈아 끼우지 않는다', () => {
    const { group, materials } = scene();
    const instance = prepareInstance(group);
    const used = instance.children.map((child) => (child as THREE.Mesh).material as THREE.MeshStandardMaterial);
    expect(used.map((m) => m.uuid)).toEqual(materials.map((m) => m.uuid));
  });

  it('색이 한 모델 안에서 갈리지 않는다 — 두 재질 다 authored 값 그대로', () => {
    const { group } = scene();
    const instance = prepareInstance(group);
    const hex = instance.children.map((c) => ((c as THREE.Mesh).material as THREE.MeshStandardMaterial).color.getHexString());
    expect(hex).toEqual(['112233', 'd9dbe6']);
  });

  it('그림자 플래그만 세운다', () => {
    const { group } = scene();
    const instance = prepareInstance(group);
    for (const child of instance.children) {
      expect(child.castShadow).toBe(true);
      expect(child.receiveShadow).toBe(true);
    }
  });

  it('원본 장면을 건드리지 않는다 — 같은 자산을 여럿 놓아도 서로 영향이 없다', () => {
    const { group } = scene();
    prepareInstance(group);
    for (const child of group.children) {
      expect(child.castShadow).toBe(false);
    }
  });
});
