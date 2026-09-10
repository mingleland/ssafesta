// Booth Studio 편집기의 조정 가능한 상수를 한 곳에 모아두는 자리 — 값 변경이 여기 한 파일로 끝나게 한다
// 출처: specs/005-booth-studio-layout/FE/research.md R-03·R-04·R-05

// 스냅 격자 간격(미터). Unity·BE 권고 0.25 확정(#19) — 6 / 0.25 = 24칸,
// 가장 작은 파츠(0.32m)보다 작아 미세 조정이 된다. 서버는 스냅을 검증하지 않는다(편집기 UX).
export const SNAP_METERS = 0.25;

// 순수 UI 표시 배율(px/m) — 화면에 몇 픽셀로 그릴지만 결정한다.
// 저장되는 JSON 좌표는 처음부터 미터라 이 값을 바꿔도 저장 데이터는 1비트도 달라지지 않는다 (R-03).
export const PX_PER_M = 60;

// 부스 크기(미터) — 정본은 GET /booth-layout-templates 응답의 footprint(§9).
// 이 값은 서버 응답 도착 전 렌더링용 기본값일 뿐이다. 6×6×2.72 확정(#19 — 높이는 셸 벽 실측 2.725의 내림).
export const BOOTH_SIZE_FALLBACK = { width: 6, depth: 6, height: 2.72 } as const;

// 오브젝트 상한 기본값 — 정본은 §9 응답의 maxObjects. 헌법 22조.
export const MAX_OBJECTS_FALLBACK = 12;

// configId 유효 범위 — signed Int32의 양수만(계약 §1, #34에서 DB CHECK(config_id > 0)로 강제).
// 0을 금지하는 이유가 따로 있다: Unity JsonUtility가 int 필드 부재를 0으로 읽어 "미연결"로 판정하므로
// 0을 유효 ID로 쓰면 연결된 오브젝트가 조용히 미연결로 렌더링된다(#45 전제).
export const CONFIG_ID_MIN = 1;
export const CONFIG_ID_MAX = 2_147_483_647;

// 캔버스 줌 (S15P21A604-604). 툴바 프리셋은 이 범위 안의 세 점이고, Ctrl+휠은 그 사이를 연속으로 움직인다.
// 상한·하한은 표시 배율일 뿐이라 저장 좌표에 영향이 없다(PX_PER_M 과 같은 성격).
export const ZOOM_MIN = 0.4;
export const ZOOM_MAX = 2.5;
/** 휠 한 칸이 바꾸는 배율 — 곱셈이라 확대·축소가 대칭이다(덧셈이면 축소 쪽이 급해진다) */
export const ZOOM_WHEEL_FACTOR = 1.1;
/** 툴바 버튼이 도는 프리셋 */
export const ZOOM_PRESETS = [0.8, 1, 1.2] as const;

export function clampZoom(zoom: number): number {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom));
}
