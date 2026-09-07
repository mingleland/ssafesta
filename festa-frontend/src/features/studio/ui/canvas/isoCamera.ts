// R3F 캔버스의 순수 계산 — three 를 import 하지 않는다.
// three 를 끌어들이면 이 값들을 테스트할 때마다 WebGL 이 필요해진다. 카메라 각도·프레이밍·
// 오브젝트 배치는 전부 산수라 렌더러 밖에서 검증할 수 있어야 한다 (D-05 Spike, S15P21A604-470).
import type { AABB } from '../../../../entities/layout/objectTypes';
import type { BoothBounds } from './canvasTypes';

/**
 * 고정 아이소메트릭 시점. (1, 1, 1) 방향은 표준 isometric 이고 수평면에서 35.264° 다.
 * D-04 가 금지한 것은 자유 orbit 이지 특정 각도가 아니다 — 이 벡터는 상수로 잠근다.
 */
export const ISO_DIRECTION: readonly [number, number, number] = [1, 1, 1];

/** 카메라를 부스 밖으로 밀어내는 거리(m). Orthographic 이라 크기에는 영향이 없고 near/far 여유만 준다 */
export const ISO_DISTANCE = 24;

export function isoCameraPosition(): [number, number, number] {
  const len = Math.sqrt(3);
  return [
    (ISO_DIRECTION[0] / len) * ISO_DISTANCE,
    (ISO_DIRECTION[1] / len) * ISO_DISTANCE,
    (ISO_DIRECTION[2] / len) * ISO_DISTANCE,
  ];
}

/** 부스 전체가 화면 안에 들어오는 여백 비율 — 1 이면 딱 맞고, 크면 헐거워진다 */
const FRAME_PADDING = 1.25;

/**
 * Orthographic zoom(픽셀/미터)을 캔버스 크기에서 구한다.
 *
 * 아이소메트릭 투영에서 가로 폭은 (W+D)·cos30, 세로는 (W+D)·sin30 + H 로 늘어난다.
 * 이 계산 없이 zoom 을 상수로 두면 캔버스가 작을 때 부스가 잘리고 클 때 우표만 해진다 —
 * SVG 렌더러가 viewBox 로 하던 일을 여기서 한다.
 */
export function fitZoom(canvas: { width: number; height: number }, bounds: BoothBounds, zoom: number): number {
  const spanX = (bounds.width + bounds.depth) * Math.cos(Math.PI / 6);
  const spanY = (bounds.width + bounds.depth) * Math.sin(Math.PI / 6) + bounds.height;
  if (spanX <= 0 || spanY <= 0) return zoom;
  const fit = Math.min(canvas.width / (spanX * FRAME_PADDING), canvas.height / (spanY * FRAME_PADDING));
  // 캔버스가 아직 0 인 첫 프레임에 zoom 0 을 넣으면 three 가 NaN 행렬을 만든다
  return Math.max(1, fit) * zoom;
}

/** 부스 중앙보다 살짝 위를 본다 — 벽·트러스가 위로 서 있어서 바닥 중앙을 보면 화면이 아래로 쏠린다 */
export function isoTarget(bounds: BoothBounds): [number, number, number] {
  return [0, bounds.height * 0.35, 0];
}

/**
 * 로컬 AABB 를 박스 mesh 하나로 옮긴다.
 *
 * 계약의 AABB 는 **중앙 원점이 아니다**(objectTypes.ts 경고) — CONSULTATION_DESK 는 z 가
 * -1.0~0.16 이다. size 만 쓰고 원점을 중앙으로 가정하면 회전할 때 실제와 어긋난다.
 * 그래서 center 를 따로 넘긴다. 부모 group 이 position/rotationY 를 갖고, 이 center 는
 * 그 안에서의 로컬 오프셋이다.
 */
export function boxPlacement(box: AABB): { center: [number, number, number]; size: [number, number, number] } {
  return {
    center: [(box.min.x + box.max.x) / 2, (box.min.y + box.max.y) / 2, (box.min.z + box.max.z) / 2],
    size: [
      Math.max(box.max.x - box.min.x, 0.01),
      Math.max(box.max.y - box.min.y, 0.01),
      Math.max(box.max.z - box.min.z, 0.01),
    ],
  };
}

/**
 * 회전 드래그의 각도 변화(도). SVG 렌더러와 같은 식이다 — 월드 평면에서 재고, 화면 각도로 재지 않는다.
 * 화면 각도로 재면 아이소 왜곡이 그대로 회전값에 섞인다.
 */
export function rotationFromDrag(
  origin: { x: number; z: number },
  grab: { x: number; z: number },
  now: { x: number; z: number },
  startRotation: number,
): number {
  const a0 = Math.atan2(grab.x - origin.x, grab.z - origin.z);
  const a1 = Math.atan2(now.x - origin.x, now.z - origin.z);
  return startRotation + ((a1 - a0) * 180) / Math.PI;
}
