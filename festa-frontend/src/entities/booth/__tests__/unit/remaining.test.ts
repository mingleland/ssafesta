// endsAt 기준 남은 시간 계산·표시 고정 — 스냅샷(remainingSeconds) 미사용 정책의 코드 반쪽
import { describe, expect, it } from 'vitest';
import { formatRemaining, remainingMs } from '../../remaining';

describe('remainingMs', () => {
  it('경과 시각은 0으로 clamp — 음수를 내지 않는다', () => {
    expect(remainingMs('2026-08-24T00:00:00Z', Date.parse('2026-08-24T01:00:00Z'))).toBe(0);
  });

  it('미래 시각은 밀리초 차이', () => {
    expect(remainingMs('2026-08-24T00:00:10Z', Date.parse('2026-08-24T00:00:00Z'))).toBe(10_000);
  });
});

describe('formatRemaining', () => {
  it('하루 미만은 HH:MM:SS', () => {
    expect(formatRemaining(3_600_000 * 3 + 60_000 * 12 + 45_000)).toBe('03:12:45');
  });

  it('하루 이상은 일 병기', () => {
    expect(formatRemaining(86_400_000 * 2 + 3_600_000 * 3)).toBe('2일 03:00:00');
  });

  it('0은 00:00:00', () => {
    expect(formatRemaining(0)).toBe('00:00:00');
  });

  it('1초 미만 잔여는 내림 — 만료 직전 00:00:00', () => {
    expect(formatRemaining(999)).toBe('00:00:00');
  });
});
