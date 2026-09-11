// 11F 부스 슬롯의 평면 배치 (S15P21A604-606).
//
// **좌표의 출처는 Unity 씬이다.** `festa-unity/Assets/_Project/Prefabs/World/Festival.prefab` 의
// `Portal_Ext_01`~`Portal_Ext_12`(각 부스 외부 출입 포털)의 Transform 을 뽑아 정규화했다.
// `main.unity` 에는 같은 boothId 로 내부 출구 포털이 하나씩 더 있는데, 그쪽은 부스 안의 좌표라
// 평면도용이 아니다.
//
// 왜 계약(`SlotView`)이 아니라 FE 상수인가: 슬롯은 12개 고정이고 `floorNo` 도 11 고정이라
// (계약이 층 필터 UI 를 금지한다) 상수 표의 유지비가 BE·Unity·FE 3파트 계약 확장보다 싸다.
// 배치가 자주 바뀌기 시작하면 그때 계약으로 올린다.
//
// 실측 원본(월드 유닛):
//   x  -887.34 ~ -278.68   (폭 608.66)
//   z  홀수 부스 ≈ 230 / 짝수 부스 ≈ 55   (폭 192.60)
//   → 6열 × 2행 통로형. 부스 번호가 커질수록 x 가 작아지므로 화면에서는 좌→우로 1→12 가 되게 뒤집었다.

/** 평면도 안에서의 위치(%) — 좌상단 기준. 그림 크기와 무관하게 쓰려고 비율로 둔다 */
export interface SlotSpot {
  left: number;
  top: number;
}

export const SLOT_SPOTS: Readonly<Record<number, SlotSpot>> = Object.freeze({
  1: { left: 6.0, top: 10.4 },
  2: { left: 9.2, top: 91.1 },
  3: { left: 24.5, top: 6.0 },
  4: { left: 26.8, top: 94.0 },
  5: { left: 40.2, top: 10.9 },
  6: { left: 43.4, top: 94.0 },
  7: { left: 54.4, top: 12.1 },
  8: { left: 61.0, top: 89.6 },
  9: { left: 71.7, top: 10.2 },
  10: { left: 77.8, top: 89.6 },
  11: { left: 90.8, top: 11.4 },
  12: { left: 94.0, top: 89.6 },
});

/** 그림을 그릴 수 있는 슬롯인가 — 표에 없는 slotId 는 평면도에 얹지 않고 목록에만 남긴다 */
export function slotSpot(slotId: number): SlotSpot | undefined {
  return SLOT_SPOTS[slotId];
}
