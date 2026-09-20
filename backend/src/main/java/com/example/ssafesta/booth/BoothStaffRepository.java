package com.example.ssafesta.booth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Membership lookups for {@link BoothAccessGuard}, plus the one write that has to be a real INSERT.
 * Everything else about staff rows belongs to spec 011 (R-07).
 */
public interface BoothStaffRepository extends JpaRepository<BoothStaff, BoothStaff.Key> {

    /**
     * The role, not just "is a member" — the guard has to tell a consultant from an editor
     * (spec 011 FR-002, C-09).
     */
    @Query("select s.role from BoothStaff s where s.boothId = :boothId and s.userId = :userId")
    Optional<String> findRole(@Param("boothId") Long boothId, @Param("userId") Long userId);

    /** The booth roster, oldest seat first — the order the owner added people in. */
    List<BoothStaff> findByBoothIdOrderByJoinedAt(Long boothId);

    Optional<BoothStaff> findByBoothIdAndUserId(Long boothId, Long userId);

    /**
     * Seat a member, and say whether this call is the one that seated them.
     *
     * <p><b>{@code save()} cannot do this.</b> {@code BoothStaff} carries an assigned
     * {@code @IdClass}, so Spring Data sees a non-null id, treats the instance as already persistent
     * and calls {@code merge()} — and merge SELECTs before it writes. Two accepts that both pass the
     * membership pre-check then split on timing: if the loser's merge-SELECT lands after the winner
     * committed, it finds the row and issues an <b>UPDATE</b>. No constraint is violated, no
     * exception is thrown, and both callers are told they succeeded.
     *
     * <p>CI caught exactly that once (GitLab #190 — {@code expected: <1> but was: <2>} in
     * {@code StaffInvitationConcurrencyIntegrationTest}); it reads as a flaky test because the
     * pre-check usually serializes the two and the loser never reaches the write.
     *
     * <p>So the outcome is read from the row count instead of from an exception. {@code joined_at}
     * and {@code consultation_status} are left to their column defaults (V1, V33) — the values the
     * entity would have supplied.
     *
     * @return 1 when this call seated the member, 0 when the seat was already taken
     */
    @Modifying
    @Query(value = """
            INSERT INTO booth_staffs (booth_id, user_id, role)
            VALUES (:boothId, :userId, :role)
            ON CONFLICT (booth_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int seatIfAbsent(@Param("boothId") Long boothId, @Param("userId") Long userId,
                     @Param("role") String role);
}
