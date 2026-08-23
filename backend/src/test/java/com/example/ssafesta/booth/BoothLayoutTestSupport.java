package com.example.ssafesta.booth;

import org.springframework.jdbc.core.JdbcTemplate;

/** Layout JSON builders and lease fixtures shared by the spec 005 tests. */
final class BoothLayoutTestSupport {

    private BoothLayoutTestSupport() {
    }

    /**
     * Attaches a valid lease to a booth so it can publish and be visited.
     *
     * <p>Inserted directly rather than through {@code BoothLeaseService}: these tests are about
     * layouts, and going through the paid path would couple them to spec 003's balances and to
     * whichever slot happens to be free in a shared database.
     *
     * @return the slot the lease landed on — the slot-keyed path (#62) has to know which room
     */
    static Long grantLease(JdbcTemplate jdbc, Long boothId, Long userId) {
        Long slotId = jdbc.queryForObject("""
                SELECT id FROM booth_slots
                 WHERE slot_type = 'USER_RENTAL'
                   AND id NOT IN (SELECT slot_id FROM booth_leases WHERE status = 'ACTIVE')
                 ORDER BY id LIMIT 1
                """, Long.class);
        grantLeaseOnSlot(jdbc, boothId, userId, slotId);
        return slotId;
    }

    /** The same, on a named room — for re-lease tests, where two booths must share one slot. */
    static void grantLeaseOnSlot(JdbcTemplate jdbc, Long boothId, Long userId, Long slotId) {
        jdbc.update("""
                INSERT INTO booth_leases (booth_id, slot_id, lessee_user_id, status, starts_at, ends_at, charged_coin)
                VALUES (?, ?, ?, 'ACTIVE', now(), now() + interval '24 hours', 100)
                """, boothId, slotId, userId);
        jdbc.update("UPDATE booths SET current_slot_id = ?, status = 'ACTIVE' WHERE id = ?", slotId, boothId);
    }

    /**
     * Ends a lease the way a re-lease does: the row leaves {@code ACTIVE} and the booth lets go of
     * the room — what {@code BoothLeaseService.releaseStaleLeases} performs (FR-017). Distinct from
     * {@link #expireLease}, which leaves the row {@code ACTIVE} with its time passed.
     */
    static void releaseLease(JdbcTemplate jdbc, Long boothId) {
        jdbc.update("UPDATE booth_leases SET status = 'EXPIRED' WHERE booth_id = ? AND status = 'ACTIVE'", boothId);
        jdbc.update("UPDATE booths SET current_slot_id = NULL, status = 'INACTIVE' WHERE id = ?", boothId);
    }

    /** Pushes a booth's lease into the past, both ends — {@code CHECK(ends_at > starts_at)} forbids one. */
    static void expireLease(JdbcTemplate jdbc, Long boothId) {
        jdbc.update("""
                UPDATE booth_leases
                   SET starts_at = now() - interval '25 hours', ends_at = now() - interval '1 hour'
                 WHERE booth_id = ? AND status = 'ACTIVE'
                """, boothId);
    }

    /**
     * A valid save request with one screen.
     *
     * <p>z가 1.4인 이유: VIDEO_SCREEN(로컬 x −1.50~+1.20)을 90° 돌리면 z 방향 실물이
     * [z−1.2, z+1.5]가 된다. 실물 검증(#19 ③) 기준으로 z ≤ 1.5여야 부스 안이다.
     */
    static String saveRequest(long expectedRevision) {
        return """
                {"expectedRevision":%d,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"screen-1","type":"VIDEO_SCREEN",
                   "position":{"x":2.1,"y":0.0,"z":1.4},"rotationY":90.0,"configId":152}]}
                """.formatted(expectedRevision);
    }

    /** A save request whose objects are supplied verbatim. */
    static String saveRequest(long expectedRevision, String objectsJson) {
        return """
                {"expectedRevision":%d,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":%s}
                """.formatted(expectedRevision, objectsJson);
    }

    static String object(String objectId, String type) {
        return """
                {"objectId":"%s","type":"%s","position":{"x":0.0,"y":0.0,"z":0.0},"rotationY":0.0}
                """.formatted(objectId, type);
    }

    /** {@code count} distinct decorations — for exercising the 12-object cap. */
    static String decorations(int count) {
        StringBuilder objects = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                objects.append(',');
            }
            objects.append(object("deco-" + index, "DECORATION"));
        }
        return objects.append(']').toString();
    }
}
