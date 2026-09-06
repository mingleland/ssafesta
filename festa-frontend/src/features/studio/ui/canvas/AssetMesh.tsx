// 실제 Unity 모델(GLB)을 하나 그린다 (S15P21A604-473).
//
// Suspense 를 쓰지 않고 직접 로드한다. useLoader 는 실패를 throw 로 올려 Canvas 안에서
// error boundary 를 따로 세워야 하고, 그러면 "무엇이 왜 안 떴는지" 가 화면에서 사라진다.
// 여기서는 상태를 손에 들고 있다가 실패를 눈에 보이게 만든다 — 조용히 기본값으로 되돌아가는
// 것이 T-24 의 원인이었다.
import { useEffect, useMemo, useState } from 'react';
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import { assetUrl } from '../../model/boothAssetManifest';

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; scene: THREE.Group }
  | { kind: 'error'; reason: string };

/** GLB 는 한 번만 받는다 — 같은 assetCode 오브젝트가 여럿이면 clone 으로 나눠 쓴다 */
const cache = new Map<string, Promise<THREE.Group>>();

function loadGlb(url: string): Promise<THREE.Group> {
  const hit = cache.get(url);
  if (hit !== undefined) return hit;
  const pending = new Promise<THREE.Group>((resolve, reject) => {
    new GLTFLoader().load(
      url,
      (gltf) => resolve(gltf.scene),
      undefined,
      (error) => reject(error instanceof Error ? error : new Error(String(error))),
    );
  });
  cache.set(url, pending);
  // 실패한 약속을 캐시에 남기면 다시 시도할 길이 없어진다
  pending.catch(() => cache.delete(url));
  return pending;
}

export function __clearBoothAssetCache(): void {
  cache.clear();
}

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
  const url = useMemo(() => assetUrl(entry.url), [entry.url]);

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

  // 인스턴스마다 재질을 따로 준다 — 하나를 공유하면 색을 바꿀 때 전부 같이 바뀐다
  const instance = useMemo(() => {
    if (state.kind !== 'ready') return null;
    const clone = state.scene.clone(true);
    const material = new THREE.MeshStandardMaterial({ color, roughness, metalness });
    clone.traverse((o) => {
      if ((o as THREE.Mesh).isMesh) {
        const mesh = o as THREE.Mesh;
        mesh.material = material;
        mesh.castShadow = true;
        mesh.receiveShadow = true;
      }
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
