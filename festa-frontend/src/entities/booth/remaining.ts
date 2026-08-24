// 임대 남은 시간 계산 — endsAt 절대시각 기준(FR-007). 서버 스냅샷(remainingSeconds)을 쓰면
// 백그라운드 탭 복귀 후 어긋나므로 어디서도 쓰지 않는다. 만료 "판정"은 서버 status가 권위(C-02) —
// 이 모듈은 표시 전용이다.

export function remainingMs(endsAt: string, now: number): number {
  return Math.max(0, Date.parse(endsAt) - now);
}

// "2일 03:12:45" / "03:12:45" — 표시 전용. 카운트다운은 epoch 차이라 타임존 무관
export function formatRemaining(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const days = Math.floor(totalSeconds / 86_400);
  const h = Math.floor((totalSeconds % 86_400) / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const hms = [h, m, s].map((n) => String(n).padStart(2, '0')).join(':');
  return days > 0 ? `${days}일 ${hms}` : hms;
}
