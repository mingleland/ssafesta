package com.example.ssafesta.booth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Can a visitor actually walk this booth? (#19 ⑤, 2026-08-21 확정 — spec 005 FR-016의 warning 축)
 *
 * <p>Publish 시점에만 돈다. 결과는 <b>경고</b>고 공개를 막지 않는다 — 뒷공간을 창고처럼 쓰는
 * 배치는 소유자의 선택일 수 있어서다. 영역 <i>이탈</i>은 남의 슬롯을 침범하는 객관적 결함이라
 * {@link LayoutValidator}가 error로 따로 막는다.
 *
 * <p>모든 파라미터는 3파트 계약값이다 — 편집기(FE)가 같은 값으로 실시간 판정을 그리므로, 여기
 * 숫자를 바꾸는 것은 코드 수정이 아니라 계약 변경이다(헌법 24조):
 *
 * <ul>
 *   <li>래스터 해상도 0.05 m — 부스 로컬 x·z ∈ [−3, +3], 셀 중심 −2.975 + 0.05k (k = 0…119),
 *       120×120
 *   <li>점유 = 회전 적용 후 AABB와 셀 중심의 포함 검사, 경계선상은 점유(보수적)
 *   <li>아바타 침식 = 점유 셀을 유클리드 반경 0.22 m 팽창 ({@code PlayerAvatar} 캡슐 반지름
 *       2.2 world unit ÷ 10)
 *   <li>flood fill 4-방향, 시작점은 +z 경계(z = +3) 쪽 비점유 셀 전부 — 정면만 개방
 *   <li>관람 띠 = 상호작용 파츠의 +z(정면) 면에서 바깥으로 0.7 m 폭, 도달 가능 비율 50% 미만이면
 *       {@code FRONT_BLOCKED}
 *   <li>침식 후 비점유인데 flood fill이 못 닿는 셀이 1 ㎡ 이상이면 {@code ISOLATED_AREA}
 * </ul>
 */
@Component
public class LayoutPassageChecker {

    static final double CELL = 0.05;
    static final int GRID = 120;
    static final double FIRST_CENTER = -2.975;
    static final double EROSION_RADIUS = 0.22;
    static final double BAND_DEPTH = 0.7;
    static final double MIN_REACHABLE_RATIO = 0.5;
    /** 1 ㎡를 셀로 환산한 값 — 셀 하나가 0.05² = 0.0025 ㎡다. */
    static final int ISOLATED_MIN_CELLS = 400;

    /** Float-noise slack only. 0.05 셀에서 판정을 뒤집을 수 없는 크기다. */
    private static final double EPS = 1e-9;

    void check(List<LayoutJson.LayoutObject> objects, LayoutValidationResult result) {
        boolean[][] occupied = new boolean[GRID][GRID];
        List<PlacedObject> placed = new ArrayList<>();
        for (LayoutJson.LayoutObject object : objects) {
            LayoutObjectType type = LayoutObjectType.from(object.type()).orElse(null);
            if (type == null || object.position() == null || object.rotationY() == null) {
                continue; // 구조 오류는 validator가 이미 error로 막았다 — 여기 오면 없을 값들이다.
            }
            PlacedObject placement = new PlacedObject(type, object.objectId(),
                    object.position().x().doubleValue(), object.position().z().doubleValue(),
                    object.rotationY().doubleValue());
            placed.add(placement);
            markOccupied(placement, occupied);
        }

        boolean[][] blocked = dilate(occupied);
        boolean[][] reached = floodFromFront(blocked);

        int strandedCells = 0;
        for (int ix = 0; ix < GRID; ix++) {
            for (int iz = 0; iz < GRID; iz++) {
                if (!blocked[ix][iz] && !reached[ix][iz]) {
                    strandedCells++;
                }
            }
        }
        if (strandedCells >= ISOLATED_MIN_CELLS) {
            double squareMetres = strandedCells * CELL * CELL;
            result.addWarning("ISOLATED_AREA",
                    "통행이 불가능한 고립 공간이 약 %.1f㎡ 있습니다. 방문자가 들어갈 수 없는 자리입니다."
                            .formatted(squareMetres));
        }

        for (PlacedObject placement : placed) {
            if (placement.type().requiresConfig()) {
                checkViewingBand(placement, reached, result);
            }
        }
    }

    private void markOccupied(PlacedObject placement, boolean[][] occupied) {
        LayoutGeometry.WorldAabb box = LayoutGeometry.worldAabb(placement.type().localBounds(),
                placement.x(), 0, placement.z(), placement.rotationY());
        int fromX = lowestCellAtOrAbove(box.minX());
        int toX = highestCellAtOrBelow(box.maxX());
        int fromZ = lowestCellAtOrAbove(box.minZ());
        int toZ = highestCellAtOrBelow(box.maxZ());
        for (int ix = Math.max(fromX, 0); ix <= Math.min(toX, GRID - 1); ix++) {
            for (int iz = Math.max(fromZ, 0); iz <= Math.min(toZ, GRID - 1); iz++) {
                occupied[ix][iz] = true;
            }
        }
    }

    private boolean[][] dilate(boolean[][] occupied) {
        // 반경 0.22 m = 4.4 셀. 마스크는 셀 중심 간 유클리드 거리로 뽑는다.
        int reach = (int) Math.floor(EROSION_RADIUS / CELL);
        List<int[]> mask = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double distance = Math.hypot(dx * CELL, dz * CELL);
                if (distance <= EROSION_RADIUS + EPS) {
                    mask.add(new int[] {dx, dz});
                }
            }
        }
        boolean[][] blocked = new boolean[GRID][GRID];
        for (int ix = 0; ix < GRID; ix++) {
            for (int iz = 0; iz < GRID; iz++) {
                if (!occupied[ix][iz]) {
                    continue;
                }
                for (int[] offset : mask) {
                    int nx = ix + offset[0];
                    int nz = iz + offset[1];
                    if (nx >= 0 && nx < GRID && nz >= 0 && nz < GRID) {
                        blocked[nx][nz] = true;
                    }
                }
            }
        }
        return blocked;
    }

    private boolean[][] floodFromFront(boolean[][] blocked) {
        boolean[][] reached = new boolean[GRID][GRID];
        Deque<int[]> queue = new ArrayDeque<>();
        int frontRow = GRID - 1; // z = +2.975, 정면(+z) 경계 쪽 줄
        for (int ix = 0; ix < GRID; ix++) {
            if (!blocked[ix][frontRow]) {
                reached[ix][frontRow] = true;
                queue.add(new int[] {ix, frontRow});
            }
        }
        int[][] steps = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] step : steps) {
                int nx = cell[0] + step[0];
                int nz = cell[1] + step[1];
                if (nx >= 0 && nx < GRID && nz >= 0 && nz < GRID
                        && !blocked[nx][nz] && !reached[nx][nz]) {
                    reached[nx][nz] = true;
                    queue.add(new int[] {nx, nz});
                }
            }
        }
        return reached;
    }

    /**
     * 관람 띠: 로컬 좌표로는 x ∈ [minX, maxX], z ∈ [maxZ, maxZ + 0.7]인 직사각형이고, 월드에서는
     * 회전한 직사각형이다. 그래서 셀 중심을 로컬로 되돌려 검사한다 — AABB로 근사하면 회전한
     * 파츠의 띠가 실제보다 넓어져 "막힘"을 놓친다.
     */
    private void checkViewingBand(PlacedObject placement, boolean[][] reached,
                                  LayoutValidationResult result) {
        LayoutObjectType.LocalBounds bounds = placement.type().localBounds();
        int total = 0;
        int reachable = 0;
        for (int ix = 0; ix < GRID; ix++) {
            for (int iz = 0; iz < GRID; iz++) {
                double centerX = FIRST_CENTER + CELL * ix;
                double centerZ = FIRST_CENTER + CELL * iz;
                LayoutGeometry.LocalPoint local = LayoutGeometry.toLocal(centerX, centerZ,
                        placement.x(), placement.z(), placement.rotationY());
                boolean inBand = local.x() >= bounds.minX() - EPS && local.x() <= bounds.maxX() + EPS
                        && local.z() >= bounds.maxZ() - EPS
                        && local.z() <= bounds.maxZ() + BAND_DEPTH + EPS;
                if (!inBand) {
                    continue;
                }
                total++;
                if (reached[ix][iz]) {
                    reachable++;
                }
            }
        }
        if (total == 0) {
            return; // 띠가 통째로 부스 밖 — 정면이 열린 입구를 보고 있다는 뜻이라 잴 것이 없다.
        }
        double ratio = (double) reachable / total;
        if (ratio < MIN_REACHABLE_RATIO) {
            result.addWarning("FRONT_BLOCKED", placement.objectId(),
                    "정면 관람 공간의 %d%%만 접근할 수 있습니다. 다른 오브젝트가 길을 막고 있습니다."
                            .formatted(Math.round(ratio * 100)));
        }
    }

    /** 경계선상 셀 중심은 포함(점유·띠 모두 보수적) — EPS는 부동소수점 잡음만 흡수한다. */
    private int lowestCellAtOrAbove(double coordinate) {
        return (int) Math.ceil((coordinate - FIRST_CENTER) / CELL - EPS);
    }

    private int highestCellAtOrBelow(double coordinate) {
        return (int) Math.floor((coordinate - FIRST_CENTER) / CELL + EPS);
    }

    private record PlacedObject(LayoutObjectType type, String objectId,
                                double x, double z, double rotationY) { }
}
