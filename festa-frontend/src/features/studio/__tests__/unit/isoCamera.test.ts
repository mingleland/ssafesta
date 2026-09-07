// R3F 캔버스의 산수 회귀 (S15P21A604-470).
// 렌더러는 jsdom 에서 WebGL 이 없어 못 돌린다. 그래서 틀리면 화면이 조용히 어긋나는 값들 —
// 카메라 각도, 프레이밍, AABB→박스 배치, 회전 델타 — 만 순수 함수로 빼서 여기서 잠근다.
import { describe, expect, it } from 'vitest';
import { OBJECT_LOCAL_BOUNDS } from '../../../../entities/layout/objectTypes';
import { boxPlacement, fitZoom, isoCameraPosition, isoTarget, rotationFromDrag } from '../../ui/canvas/isoCamera';

const BOUNDS = { width: 6, depth: 4, height: 3 };

describe('아이소메트릭 카메라', () => {
  it('세 성분이 같다 — (1,1,1) 방향이라야 표준 아이소메트릭이다', () => {
    const [x, y, z] = isoCameraPosition();
    expect(x).toBeCloseTo(y, 10);
    expect(y).toBeCloseTo(z, 10);
    expect(x).toBeGreaterThan(0);
  });

  it('수평면에서 35.264° 로 내려다본다', () => {
    const [x, y, z] = isoCameraPosition();
    const elevation = (Math.atan2(y, Math.hypot(x, z)) * 180) / Math.PI;
    expect(elevation).toBeCloseTo(35.264, 2);
  });

  it('바닥 중앙이 아니라 살짝 위를 본다 — 벽·트러스가 위로 서 있다', () => {
    expect(isoTarget(BOUNDS)[1]).toBeGreaterThan(0);
  });
});

describe('프레이밍 — 캔버스가 바뀌어도 부스가 잘리지 않는다', () => {
  it('캔버스가 커지면 zoom 도 커진다', () => {
    const small = fitZoom({ width: 600, height: 400 }, BOUNDS, 1);
    const large = fitZoom({ width: 1200, height: 800 }, BOUNDS, 1);
    expect(large).toBeGreaterThan(small);
  });

  it('zoom 배율은 곱해진다 — 툴바의 80/100/120% 가 그대로 반영돼야 한다', () => {
    const base = fitZoom({ width: 900, height: 700 }, BOUNDS, 1);
    expect(fitZoom({ width: 900, height: 700 }, BOUNDS, 1.2)).toBeCloseTo(base * 1.2, 6);
  });

  it('부스가 크면 zoom 이 작아진다 — 큰 부스가 화면 밖으로 나가면 안 된다', () => {
    const smallBooth = fitZoom({ width: 900, height: 700 }, BOUNDS, 1);
    const bigBooth = fitZoom({ width: 900, height: 700 }, { width: 12, depth: 10, height: 4 }, 1);
    expect(bigBooth).toBeLessThan(smallBooth);
  });

  it('캔버스가 아직 0 이어도 양수를 준다 — zoom 0 은 three 에서 NaN 행렬이 된다', () => {
    expect(fitZoom({ width: 0, height: 0 }, BOUNDS, 1)).toBeGreaterThan(0);
  });
});

describe('AABB → 박스 배치', () => {
  it('중앙 원점이 아닌 타입도 실제 부피 위치에 놓인다', () => {
    // CONSULTATION_DESK 는 z 가 -1.0~0.16 이다. size 만 쓰고 중앙 원점을 가정하면 어긋난다
    const { center, size } = boxPlacement(OBJECT_LOCAL_BOUNDS.CONSULTATION_DESK);
    expect(center[2]).toBeCloseTo(-0.42, 6);
    expect(size[2]).toBeCloseTo(1.16, 6);
  });

  it('바닥에 붙는다 — 전 타입의 min.y 는 0 이므로 center.y = 높이/2', () => {
    for (const box of Object.values(OBJECT_LOCAL_BOUNDS)) {
      const { center, size } = boxPlacement(box);
      expect(center[1]).toBeCloseTo(size[1] / 2, 6);
    }
  });

  it('납작한 면도 0 이 되지 않는다 — 두께 0 인 박스는 three 에서 사라진다', () => {
    const flat = { min: { x: 0, y: 0, z: 0 }, max: { x: 0, y: 1, z: 0 } };
    const { size } = boxPlacement(flat);
    expect(size[0]).toBeGreaterThan(0);
    expect(size[2]).toBeGreaterThan(0);
  });
});

describe('회전 드래그', () => {
  it('월드 평면 각도로 잰다 — 90° 를 돌리면 90° 가 더해진다', () => {
    const origin = { x: 0, z: 0 };
    const deg = rotationFromDrag(origin, { x: 0, z: 1 }, { x: 1, z: 0 }, 0);
    expect(deg).toBeCloseTo(90, 6);
  });

  it('시작 회전값 위에 누적된다', () => {
    const deg = rotationFromDrag({ x: 0, z: 0 }, { x: 0, z: 1 }, { x: 1, z: 0 }, 45);
    expect(deg).toBeCloseTo(135, 6);
  });

  it('안 움직이면 그대로다 — 클릭만 해도 값이 튀면 안 된다', () => {
    const g = { x: 0.3, z: 0.7 };
    expect(rotationFromDrag({ x: 0, z: 0 }, g, g, 30)).toBeCloseTo(30, 6);
  });
});
