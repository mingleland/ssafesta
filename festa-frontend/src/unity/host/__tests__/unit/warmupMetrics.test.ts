// cold/warm 비교에 쓰는 숫자가 실제 브라우저 기록에서 나오는지 (S15P21A604-430)
import { afterEach, describe, expect, it, vi } from 'vitest';
import { collectUnityAssetTimings, summarizeUnityAssetTimings } from '../../warmupMetrics';

const entry = (name: string, transferSize: number, decoded: number) =>
  ({ name, duration: 12.4, transferSize, encodedBodySize: decoded, decodedBodySize: decoded }) as PerformanceResourceTiming;

afterEach(() => vi.unstubAllGlobals());

describe('collectUnityAssetTimings', () => {
  it('Unity 자산만 고르고 캐시 적중을 표시한다', () => {
    vi.stubGlobal('performance', {
      getEntriesByType: () => [
        entry('https://cdn/unity/manifest.json', 300, 120),
        entry('https://cdn/unity/Build/a.wasm', 0, 9_000_000),
        entry('https://cdn/unity/Build/a.data', 40_000_000, 40_000_000),
        entry('https://cdn/other/logo.png', 5000, 5000),
      ],
    });
    const timings = collectUnityAssetTimings('https://cdn/unity');
    expect(timings.map((t) => t.name.split('/').pop())).toEqual(['manifest.json', 'a.wasm', 'a.data']);
    expect(timings[1].fromCache).toBe(true);
    expect(timings[2].fromCache).toBe(false);
  });

  it('performance API 가 없으면 빈 배열 — 계측 불가가 오류가 되지 않는다', () => {
    vi.stubGlobal('performance', undefined);
    expect(collectUnityAssetTimings('https://cdn/unity')).toEqual([]);
  });
});

describe('summarizeUnityAssetTimings', () => {
  it('전송 바이트와 캐시 적중 수를 합산한다', () => {
    const summary = summarizeUnityAssetTimings([
      { name: 'a', duration: 1, transferSize: 0, encodedBodySize: 10, decodedBodySize: 10, fromCache: true },
      { name: 'b', duration: 1, transferSize: 500, encodedBodySize: 500, decodedBodySize: 900, fromCache: false },
    ]);
    expect(summary).toEqual({ count: 2, transferredBytes: 500, decodedBytes: 910, cachedCount: 1 });
  });
});
