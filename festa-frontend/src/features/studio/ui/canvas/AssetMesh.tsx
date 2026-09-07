// 실제 Unity 모델(GLB)을 하나 그린다 (S15P21A604-473).
//
// Suspense 를 쓰지 않고 직접 로드한다. useLoader 는 실패를 throw 로 올려 Canvas 안에서
// error boundary 를 따로 세워야 하고, 그러면 "무엇이 왜 안 떴는지" 가 화면에서 사라진다.
// 여기서는 상태를 손에 들고 있다가 실패를 눈에 보이게 만든다 — 조용히 기본값으로 되돌아가는
// 것이 T-24 의 원인이었다.
import { useEffect, useMemo, useState } from 'react';
import * as THREE from 'three';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import { boothAssetBaseUrl } from '../../model/boothAssetManifest';
import { resolveAssetUrl } from '../../../../shared/assets/resolveAssetUrl';
import { loadGlb } from './boothAssetCache';

/** Compiler 가 넣는 세 채널 중 하나라도 붙어 있으면 "텍스처를 든 재질" 로 본다 */
export function hasTexture(material: THREE.Material | THREE.Material[]): boolean {
  const list = Array.isArray(material) ? material : [material];
  return list.some((m) => {
    const standard = m as THREE.MeshStandardMaterial;
    return standard.map != null || standard.normalMap != null || standard.metalnessMap != null;
  });
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; scene: THREE.Group }
  | { kind: 'error'; reason: string };

interface Props {
  entry: BoothAssetEntry;
  /** 계약 색 — 텍스처를 안 가져오므로 재질 색은 런타임이 준다 */
  color: string;
  roughness: number;
  metalness: number;
  /** 로딩·실패 동안 대신 보여 줄 것(파라메트릭 박스) */
  fallback: React.ReactNode;
}

export function AssetMesh({ entry, color, roughness, metalness, fallback }: Props) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const url = useMemo(() => resolveAssetUrl(entry.url, boothAssetBaseUrl()), [entry.url]);

  useEffect(() => {
    let alive = true;
    setState({ kind: 'loading' });
    loadGlb(url).then(
      (scene) => {
        if (alive) setState({ kind: 'ready', scene });
      },
      (error: Error) => {
        if (!alive) return;
        // 이유를 남긴다. 모델이 안 떠도 편집은 계속돼야 하지만, 왜 안 떴는지는 알아야 한다
        console.error(`[BoothAsset] ${entry.assetCode} 로드 실패 — ${url}: ${error.message}`);
        setState({ kind: 'error', reason: error.message });
      },
    );
    return () => {
      alive = false;
    };
  }, [url, entry.assetCode]);

  /**
   * 인스턴스마다 재질을 따로 준다 — 하나를 공유하면 색을 바꿀 때 전부 같이 바뀐다.
   *
   * **텍스처가 있는 재질은 덮어쓰지 않는다.** `-473` 시점의 GLB 는 텍스처가 없어서 런타임이
   * 계약 색을 통째로 씌웠는데, `-480` Runtime Asset Compiler 가 baseColor·normal·
   * metallicRoughness 를 GLB 안에 넣은 뒤에도 그 덮어쓰기가 남아 **받은 텍스처를 버리고 있었다**
   * (79,832 B 를 내려받고 흰 덩어리로 그렸다).
   *
   * 그래서 판정을 재질별로 한다 — 맵을 하나라도 든 재질은 그대로 두고, 맵이 없는 재질만
   * 계약 색으로 채운다. 계약 색은 "텍스처가 없을 때의 기본값" 이지 텍스처를 이기는 값이 아니다.
   */
  const instance = useMemo(() => {
    if (state.kind !== 'ready') return null;
    const clone = state.scene.clone(true);
    const flat = new THREE.MeshStandardMaterial({ color, roughness, metalness });
    clone.traverse((o) => {
      const mesh = o as THREE.Mesh;
      if (!mesh.isMesh) return;
      if (!hasTexture(mesh.material)) mesh.material = flat;
      mesh.castShadow = true;
      mesh.receiveShadow = true;
    });
    return clone;
  }, [state, color, roughness, metalness]);

  if (state.kind === 'error') {
    // 실패는 빨간 윤곽으로 드러낸다. 박스가 그대로 있으면 "원래 이런 것" 으로 읽힌다
    return (
      <group>
        {fallback}
        <mesh position={[0, entry.bounds.max[1] + 0.18, 0]}>
          <sphereGeometry args={[0.09, 10, 10]} />
          <meshBasicMaterial color="#ff5d5d" />
        </mesh>
      </group>
    );
  }
  if (instance === null) return <>{fallback}</>;
  return <primitive object={instance} />;
}
