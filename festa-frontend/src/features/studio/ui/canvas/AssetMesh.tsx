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

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; scene: THREE.Group }
  | { kind: 'error'; reason: string };

/**
 * 장면 하나를 인스턴스로 뜬다 — **재질은 손대지 않는다** (S15P21A604-789).
 *
 * 순수 함수로 떼어 둔 것은 이 규칙을 R3F 없이 잠그기 위해서다. 재질을 다시 갈아 끼우는
 * 변경이 들어오면 테스트가 먼저 깨진다.
 */
export function prepareInstance(scene: THREE.Group): THREE.Group {
  const clone = scene.clone(true);
  clone.traverse((o) => {
    const mesh = o as THREE.Mesh;
    if (!mesh.isMesh) return;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
  });
  return clone;
}

interface Props {
  entry: BoothAssetEntry;
  /** 로딩·실패 동안 대신 보여 줄 것(파라메트릭 박스) */
  fallback: React.ReactNode;
}

export function AssetMesh({ entry, fallback }: Props) {
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
   * **GLB 가 들고 온 재질을 그대로 쓴다** (S15P21A604-789).
   *
   * 전에는 "맵을 든 재질은 두고 맵이 없는 재질만 계약 색으로 채운다" 였다. 그 판정은 축이
   * 틀렸다 — Runtime Asset Compiler 가 굽는 GLB 는 원본 `.mat` 의 `_BaseColor` 를 항상
   * `baseColorFactor` 로 옮겨 담는다. 즉 **맵이 없어도 색은 있다.**
   *
   * 그런데 벤더 원본 중에는 albedo 맵 없이 normal·metallicRoughness 만 든 재질이 많다
   * (`PlasticWhite.mat` 의 `_BaseMap`·`_MainTex` 가 둘 다 fileID 0). 그래서 옛 판정으로는
   * 그 재질만 계약 색을 건너뛰고, 맵이 하나도 없는 형제 재질은 덮어써서 **한 모델 안에서
   * 면마다 색이 갈렸다** (`FURN_COUNTER_02` 는 재질 3개가 정확히 그 상태다).
   *
   * 계약 색은 자산이 없을 때 그리는 파라메트릭 박스의 색이지 실물 자산에 씌우는 색이 아니다.
   * 금지 상태 표시는 재질이 아니라 윤곽이 맡는다(`ObjectMesh`).
   */
  const instance = useMemo(() => (state.kind === 'ready' ? prepareInstance(state.scene) : null), [state]);

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
