export interface FramePerformanceSummary {
  readonly fps: number;
  readonly p95FrameMs: number;
  readonly slowFramePercent: number;
  readonly sampleCount: number;
  readonly meetsTarget: boolean;
}

const percentile = (sorted: readonly number[], ratio: number): number => {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
  return sorted[index] ?? 0;
};

export const summarizeFramePerformance = (
  frameDurations: readonly number[],
  elapsedMs: number,
  targetFps = 55,
): FramePerformanceSummary => {
  const validDurations = frameDurations.filter((duration) => Number.isFinite(duration) && duration >= 0);
  const sorted = [...validDurations].sort((left, right) => left - right);
  const slowFrameThreshold = 1_000 / targetFps;
  const slowFrames = validDurations.filter((duration) => duration > slowFrameThreshold).length;
  const fps = elapsedMs <= 0 ? 0 : Math.round((validDurations.length * 1_000) / elapsedMs);
  const p95FrameMs = Math.round(percentile(sorted, .95) * 10) / 10;
  const slowFramePercent = validDurations.length === 0 ? 0 : Math.round((slowFrames * 1_000) / validDurations.length) / 10;
  return {
    fps,
    p95FrameMs,
    slowFramePercent,
    sampleCount: validDurations.length,
    meetsTarget: fps >= targetFps && p95FrameMs <= slowFrameThreshold && slowFramePercent <= 5,
  };
};
