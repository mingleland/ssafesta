import { describe, expect, it } from 'vitest';
import { summarizeFramePerformance } from '../../runtime/reference/framePerformance.ts';

describe('Game Studio frame performance summary', () => {
  it('passes a stable active-tab 60fps sample', () => {
    const result = summarizeFramePerformance(Array.from({ length: 60 }, () => 16.6), 1_000);

    expect(result).toEqual({
      fps: 60,
      p95FrameMs: 16.6,
      slowFramePercent: 0,
      sampleCount: 60,
      meetsTarget: true,
    });
  });

  it('reports p95 and slow-frame ratio for a stuttering sample', () => {
    const result = summarizeFramePerformance([
      ...Array.from({ length: 45 }, () => 16),
      ...Array.from({ length: 10 }, () => 32),
    ], 1_000);

    expect(result).toMatchObject({
      fps: 55,
      p95FrameMs: 32,
      slowFramePercent: 18.2,
      meetsTarget: false,
    });
  });

  it('ignores invalid durations and handles an empty measurement window', () => {
    expect(summarizeFramePerformance([Number.NaN, Number.POSITIVE_INFINITY, -1], 0)).toEqual({
      fps: 0,
      p95FrameMs: 0,
      slowFramePercent: 0,
      sampleCount: 0,
      meetsTarget: false,
    });
  });
});
