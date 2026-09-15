// manifest 를 한 번 읽어 캔버스에 넘긴다 (S15P21A604-473).
//
// React Query 를 쓰지 않는다 — 이 값을 쓰는 곳이 R3F Canvas 경계 근처라 앱 context 에
// 기대지 않는 편이 안전하고, 요청도 앱 수명 동안 한 번이면 된다. 저장소의 module store +
// useSyncExternalStore 관행(overlay.ts·screenAudio.ts)을 그대로 따른다.
//
// manifest 가 **없는 것이 기본 상태**다. 산출물을 커밋하지 않기 때문이다(벤더 에셋 재배포 미확인).
// 없으면 빈 목록을 주고 렌더러는 파라메트릭 박스로 그린다.
import { useSyncExternalStore } from 'react';
import type { BoothAssetEntry } from './boothAssetManifest';
import { fetchBoothAssetManifest, pickAsset } from './boothAssetManifest';
import { OBJECT_LOCAL_BOUNDS } from '../../../entities/layout/objectTypes';
import type { AABB } from '../../../entities/layout/objectTypes';
import type { ObjectType } from '../../../entities/layout/types';

type Phase = 'idle' | 'loading' | 'ready';

let phase: Phase = 'idle';
let assets: BoothAssetEntry[] = [];
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (phase === 'idle') {
    phase = 'loading';
    void fetchBoothAssetManifest().then((result) => {
      phase = 'ready';
      if (result.kind === 'ready') {
        assets = result.manifest.assets;
      } else if (result.kind === 'error') {
        // 없음(404)은 정상이라 조용하다. 그 밖의 실패는 이유를 남긴다 — 조용한 기본값 복귀가 T-24 다
        console.error(`[BoothAsset] manifest 를 읽지 못했다 — ${result.reason}. 파라메트릭 박스로 그린다`);
      }
      emit();
    });
  }
  return () => {
    listeners.delete(listener);
  };
}

const getSnapshot = () => assets;

/** 서버 렌더는 없지만 useSyncExternalStore 계약상 필요하다 */
const getServerSnapshot = () => assets;

export function useBoothAssets(): BoothAssetEntry[] {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

/**
 * 훅 밖에서 같은 스냅샷을 읽는다 — 순수 판정 함수(검증·통행·겹침)가 쓴다.
 *
 * React 를 거치지 않는 것이 문제가 되지 않는 이유: manifest 는 앱 수명 동안 한 번 채워지고,
 * `StudioPage` 가 `useBoothAssets()` 로 구독하고 있어 도착하는 순간 스튜디오 하위 트리가
 * 통째로 다시 그려진다. 판정은 그 렌더에서 새 값으로 다시 돈다.
 */
export function getBoothAssets(): BoothAssetEntry[] {
  return assets;
}

function aabbOf(b: BoothAssetEntry['bounds']): AABB {
  return {
    min: { x: b.min[0], y: b.min[1], z: b.min[2] },
    max: { x: b.max[0], y: b.max[1], z: b.max[2] },
  };
}

/**
 * 오브젝트 하나의 로컬 AABB (S15P21A604-785, GitLab #181).
 *
 * **정본은 runtime manifest 의 실측 bbox 다.** 해석 순서는 `pickAsset` 그대로 —
 * `assetCode` 정확일치 → 그 타입의 `typeDefault` 자산. Unity `BoothObjectRegistry` 와 같은 순서다.
 *
 * `OBJECT_LOCAL_BOUNDS` 는 **fallback 으로만** 남는다. 두 경우에 쓰인다:
 *   ① 기능형 오브젝트(AI_AGENT·PROJECT_PANEL 등) — 2.5D manifest 에 자산이 없다
 *   ② manifest 미생성·미로드 — 산출물을 커밋하지 않으므로 **없는 것이 기본 상태**다
 * 그래서 manifest 가 없는 환경에서는 이 함수가 오늘과 완전히 같은 값을 준다(회귀 0).
 *
 * 표를 손으로 옮겨 적지 않는 이유가 이것이다 — 옮기는 순간 파이프라인이 자산을 다시 구울 때마다
 * manifest 와 FE 상수가 갈린다. 대조는 `boothAssetManifest.test.ts` 의 `boundsDelta` 가 한다.
 */
export function resolveLocalBounds(
  object: { type: ObjectType; assetCode?: string },
  assets: BoothAssetEntry[] = getBoothAssets(),
): AABB | undefined {
  const entry = pickAsset(assets, object);
  return entry === undefined ? OBJECT_LOCAL_BOUNDS[object.type] : aabbOf(entry.bounds);
}

/** 테스트 전용 — 모듈 상태를 되돌린다 */
export function __resetBoothAssetsForTests(next: BoothAssetEntry[] = []): void {
  phase = next.length > 0 ? 'ready' : 'idle';
  assets = next;
  emit();
}
