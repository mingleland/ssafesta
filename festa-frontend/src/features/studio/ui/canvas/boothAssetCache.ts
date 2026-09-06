// GLB 로드 캐시 (S15P21A604-473).
//
// AssetMesh.tsx 밖에 둔다. 컴포넌트 파일이 컴포넌트 아닌 것을 같이 내보내면 fast refresh 가
// 그 파일을 통째로 새로 평가한다 — 캐시가 편집할 때마다 날아가고, oxlint 도 그것을 경고한다.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

const cache = new Map<string, Promise<THREE.Group>>();

/** 같은 URL 은 한 번만 받는다 — 같은 assetCode 오브젝트가 여럿이면 clone 으로 나눠 쓴다 */
export function loadGlb(url: string): Promise<THREE.Group> {
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
  // 실패한 약속을 남기면 다시 시도할 길이 없어진다
  pending.catch(() => cache.delete(url));
  return pending;
}

export function clearBoothAssetCache(): void {
  cache.clear();
}
