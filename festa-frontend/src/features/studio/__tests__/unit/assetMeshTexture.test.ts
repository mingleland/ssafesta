// GLB 가 들고 온 텍스처를 런타임이 덮어쓰지 않는지 (S15P21A604-480).
//
// `-473` 시점의 GLB 는 텍스처가 없어서 `AssetMesh` 가 모든 mesh 재질을 계약 색 하나로 갈아
// 끼웠다. `-480` Runtime Asset Compiler 가 baseColor·normal·metallicRoughness 를 GLB 안에
// 넣은 뒤에도 그 덮어쓰기가 남아, **79,832 B 를 내려받고 흰 덩어리로 그렸다.**
//
// 판정은 재질별이다 — 맵을 하나라도 든 재질은 그대로 두고, 맵이 없는 재질만 계약 색으로 채운다.
import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { hasTexture } from '../../ui/canvas/AssetMesh';

const tex = () => new THREE.Texture();

describe('hasTexture — 계약 색이 텍스처를 이기지 않는다', () => {
  it('맵이 하나도 없으면 false — 계약 색으로 채워도 되는 재질이다', () => {
    expect(hasTexture(new THREE.MeshStandardMaterial({ color: '#fff' }))).toBe(false);
  });

  it.each([
    ['baseColor', 'map'],
    ['normal', 'normalMap'],
    ['metallicRoughness', 'metalnessMap'],
  ] as const)('Compiler 가 넣는 %s 채널이 있으면 true', (_label, key) => {
    const material = new THREE.MeshStandardMaterial();
    material[key] = tex();
    expect(hasTexture(material)).toBe(true);
  });

  it('멀티 머티리얼은 하나라도 맵이 있으면 true — 일부만 텍스처인 모델을 통째로 덮지 않는다', () => {
    const plain = new THREE.MeshStandardMaterial();
    const textured = new THREE.MeshStandardMaterial();
    textured.map = tex();
    expect(hasTexture([plain, textured])).toBe(true);
    expect(hasTexture([plain, new THREE.MeshStandardMaterial()])).toBe(false);
  });
});
