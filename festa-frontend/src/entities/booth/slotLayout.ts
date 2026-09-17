// 축제장 부스 슬롯 배치 (S15P21A604-606 · 2026-09-17 재작성).
//
// **정본은 Unity 월드다.** `Festival.prefab` 의 `FestivalSlot_01`~`12` Transform 실측:
//
//   슬롯   01/02   03/04   05/06   07/08   09/10   11/12
//   world x  -300    -415    -530    -645    -760    -875      (홀짝 쌍이 같은 x)
//   world z  홀수 230 · 짝수 60                                 (두 값뿐)
//
// 화면 투영은 Unity 가 이미 정해 두었다 — `FestivalMinimapArea.Normalized`:
//   가로 u = InverseLerp(z: -40 … 330)      → z 가 큰 **홀수가 오른쪽**
//   세로 v = 1 - InverseLerp(x: -930 … -240) → x 가 큰 **01·02 가 아래(입구)**
// 주석 그대로 "입구가 아래, 안쪽이 위다 — 걸어 들어가면 점이 올라간다".
//
// 그래서 실제 구조는 **2열 × 6행**이다. 옛 표는 이것을 6열 × 2행으로 눕혀 두어 월드와 90° 어긋나
// 있었고, 번호도 좌→우 1→12 로 다시 매겨 미니맵과 맞지 않았다.
//
// 좌표를 %로 굳히지 않는다. 지시문대로 **실측 재현이 아니라 정돈된 추상 맵**이라 행·열만 주고
// 간격은 CSS grid 가 균등하게 만든다.

/** 슬롯이 앉는 칸. row 1 = 입구 쪽(아래), row 6 = 안쪽(위) */
export interface SlotCell {
  row: number;
  side: 'left' | 'right';
}

export const SLOT_ROWS = 6;

/** 슬롯 번호 → 칸. 홀수는 오른쪽(z 230), 짝수는 왼쪽(z 60), 두 칸이 한 행을 이룬다 */
export function slotCell(slotId: number): SlotCell | undefined {
  if (!Number.isInteger(slotId) || slotId < 1 || slotId > SLOT_ROWS * 2) return undefined;
  return { row: Math.ceil(slotId / 2), side: slotId % 2 === 1 ? 'right' : 'left' };
}

/**
 * 그리는 순서 — **위(안쪽)에서 아래(입구)로**, 각 행은 [왼쪽, 오른쪽].
 * `[[12,11],[10,9],[8,7],[6,5],[4,3],[2,1]]` 이 되고 미니맵과 같은 그림이다.
 */
export const SLOT_GRID: readonly (readonly [number, number])[] = Object.freeze(
  Array.from({ length: SLOT_ROWS }, (_, index) => {
    const row = SLOT_ROWS - index; // 첫 줄이 가장 안쪽
    return Object.freeze([row * 2, row * 2 - 1]) as readonly [number, number];
  }),
);
