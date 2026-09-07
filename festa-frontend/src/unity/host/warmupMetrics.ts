// warm-up 이 실제로 효과가 있었는지 재는 자리 (S15P21A604-430).
// "받는 것처럼 보이는 코드" 로 끝나지 않게, 브라우저가 기록한 실 전송량을 그대로 읽는다.
// 프로덕션 화면에서 자동으로 부르지 않는다 — 계측할 때 콘솔·테스트에서 부른다.
import { unityBuildBase } from '../../shared/config/runtime';

export interface UnityAssetTiming {
  name: string;
  /** ms */
  duration: number;
  /** 실제로 네트워크를 탄 바이트. 캐시 적중이면 0 이거나 아주 작다 */
  transferSize: number;
  encodedBodySize: number;
  decodedBodySize: number;
  /** transferSize 가 0 이고 본문이 있으면 캐시에서 나온 것이다 */
  fromCache: boolean;
}

const UNITY_ASSET_PATTERN = /manifest\.json$|\.loader\.js$|\.framework\.js$|\.wasm$|\.data$/;

export function collectUnityAssetTimings(base = unityBuildBase()): UnityAssetTiming[] {
  if (typeof performance === 'undefined' || typeof performance.getEntriesByType !== 'function') return [];
  const entries = performance.getEntriesByType('resource') as PerformanceResourceTiming[];
  return entries
    .filter((entry) => UNITY_ASSET_PATTERN.test(entry.name) && (base === '' || entry.name.includes(base)))
    .map((entry) => ({
      name: entry.name,
      duration: Math.round(entry.duration),
      transferSize: entry.transferSize,
      encodedBodySize: entry.encodedBodySize,
      decodedBodySize: entry.decodedBodySize,
      fromCache: entry.transferSize === 0 && entry.decodedBodySize > 0,
    }));
}

/** 한 줄 요약 — cold/warm 비교에 쓰는 숫자 */
export function summarizeUnityAssetTimings(timings: UnityAssetTiming[]): {
  count: number;
  transferredBytes: number;
  decodedBytes: number;
  cachedCount: number;
} {
  return {
    count: timings.length,
    transferredBytes: timings.reduce((sum, t) => sum + t.transferSize, 0),
    decodedBytes: timings.reduce((sum, t) => sum + t.decodedBodySize, 0),
    cachedCount: timings.filter((t) => t.fromCache).length,
  };
}
