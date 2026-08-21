package com.example.ssafesta.booth;

/**
 * The one place rotation math lives, shared by the extent check and the passage raster.
 *
 * <p>Two callers computing "where does this object actually sit" independently is how the editor
 * and the server would drift apart — the very failure #19 ⑤ fixed the parameters against. The
 * convention is 헌법 21조's: {@code rotationY}는 도(degree), 0이면 {@code +Z}(부스 정면)를 보고,
 * 위에서 볼 때 시계 방향이 +다. Unity의 Y축 회전과 같은 식이라 세 파트가 같은 행렬을 쓴다:
 * {@code x' = x·cos + z·sin}, {@code z' = −x·sin + z·cos}.
 *
 * <p>회전은 90° 스왑이 아니라 <b>원점 기준 코너 회전 후 AABB 재계산</b>이다(#19 ③ 확정) —
 * {@code rotationY}가 0~360 연속값이기 때문이다.
 */
final class LayoutGeometry {

    private LayoutGeometry() {
    }

    /** An object's world-space AABB: local bounds rotated about the origin, then translated. */
    record WorldAabb(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ) { }

    static WorldAabb worldAabb(LayoutObjectType.LocalBounds bounds,
                               double positionX, double positionY, double positionZ,
                               double rotationYDegrees) {
        double radians = Math.toRadians(rotationYDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double[] cornerX = {bounds.minX(), bounds.minX(), bounds.maxX(), bounds.maxX()};
        double[] cornerZ = {bounds.minZ(), bounds.maxZ(), bounds.minZ(), bounds.maxZ()};

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double rotatedX = cornerX[corner] * cos + cornerZ[corner] * sin;
            double rotatedZ = -cornerX[corner] * sin + cornerZ[corner] * cos;
            minX = Math.min(minX, rotatedX);
            maxX = Math.max(maxX, rotatedX);
            minZ = Math.min(minZ, rotatedZ);
            maxZ = Math.max(maxZ, rotatedZ);
        }
        return new WorldAabb(minX + positionX, bounds.minY() + positionY, minZ + positionZ,
                maxX + positionX, bounds.maxY() + positionY, maxZ + positionZ);
    }

    /**
     * A world point expressed in an object's local frame — the inverse of the rotation above.
     * Used for the viewing-band test, where the band is an axis-aligned rectangle <i>locally</i>
     * but a rotated one in the world.
     */
    record LocalPoint(double x, double z) { }

    static LocalPoint toLocal(double worldX, double worldZ,
                              double positionX, double positionZ, double rotationYDegrees) {
        double radians = Math.toRadians(rotationYDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dx = worldX - positionX;
        double dz = worldZ - positionZ;
        return new LocalPoint(dx * cos - dz * sin, dx * sin + dz * cos);
    }
}
