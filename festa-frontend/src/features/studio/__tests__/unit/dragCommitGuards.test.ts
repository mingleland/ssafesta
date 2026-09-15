// 클릭과 드래그를 가르는 두 가드 (S15P21A604-689, GitLab #182).
//
// 제보는 "선택하려고 클릭만 했는데 오브젝트가 움직이고 회전까지 바뀐다" 였다. 실측으로 두 원인이 나왔다 —
// ① 커밋에 임계값이 없어 손떨림 1~3 px 의 pointermove 한 번이 곧바로 좌표를 스냅 격자로 끌어간다
// ② 회전 그랩이 피벗 위면 각도가 정의되지 않아 1 px 이 44°, 2 px 이 63° 로 커밋된다.
// 렌더러는 jsdom 에서 WebGL 이 없어 못 돌리므로 판정만 순수 함수로 빼서 여기서 잠근다.
import { describe, expect, it } from 'vitest';
import { DRAG_THRESHOLD_PX, exceedsDragThreshold } from '../../model/studioMode';
import { MIN_ROTATE_RADIUS_M, canRotateFrom } from '../../ui/canvas/isoCamera';

const start = { x: 400, y: 300 };

describe('드래그 임계값 — 흔들림은 선택이고 그 위는 드래그다', () => {
  it('제자리 클릭은 넘지 않는다', () => {
    expect(exceedsDragThreshold(start, { x: 400, y: 300 })).toBe(false);
  });

  it('손떨림 범위(1~3 px)는 넘지 않는다', () => {
    expect(exceedsDragThreshold(start, { x: 401, y: 300 })).toBe(false);
    expect(exceedsDragThreshold(start, { x: 402, y: 301 })).toBe(false);
    expect(exceedsDragThreshold(start, { x: 400, y: 303 })).toBe(false);
  });

  it('의도적인 이동은 넘는다 — 축 방향이든 대각이든', () => {
    expect(exceedsDragThreshold(start, { x: 405, y: 300 })).toBe(true);
    expect(exceedsDragThreshold(start, { x: 404, y: 303 })).toBe(true);
    expect(exceedsDragThreshold(start, { x: 430, y: 320 })).toBe(true);
  });

  it('기준은 화면 픽셀이다 — 줌이 달라도 같은 흔들림은 같게 판정된다', () => {
    expect(DRAG_THRESHOLD_PX).toBe(5);
    expect(exceedsDragThreshold(start, { x: 400 + DRAG_THRESHOLD_PX, y: 300 })).toBe(true);
  });
});

describe('회전 그랩 반경 — 피벗 위에서는 각도가 정의되지 않는다', () => {
  const origin = { x: 2, z: -2 };

  it('제보에서 실제로 각도를 폭발시킨 그랩(피벗에서 0.004 m)은 거부한다', () => {
    expect(canRotateFrom(origin, { x: 2.0036, z: -1.9849 })).toBe(false);
  });

  it('가장 작은 오브젝트의 반폭(0.16 m)도 아직 안쪽이다', () => {
    expect(canRotateFrom(origin, { x: 2.16, z: -2 })).toBe(false);
  });

  it('가장자리·기즈모 링 거리에서는 회전한다', () => {
    expect(canRotateFrom(origin, { x: 2 + MIN_ROTATE_RADIUS_M + 0.01, z: -2 })).toBe(true);
    expect(canRotateFrom(origin, { x: 2.8, z: -2.6 })).toBe(true);
  });
});
