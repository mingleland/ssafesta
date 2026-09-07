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
import { fetchBoothAssetManifest } from './boothAssetManifest';

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

/** 테스트 전용 — 모듈 상태를 되돌린다 */
export function __resetBoothAssetsForTests(next: BoothAssetEntry[] = []): void {
  phase = next.length > 0 ? 'ready' : 'idle';
  assets = next;
  emit();
}
