package com.example.ssafesta.booth;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Lease queries — and the <b>only</b> place the expiry rule is written down.
 *
 * <p>A lease is valid when {@code status = ACTIVE AND ends_at > now}. Services must go through
 * these methods instead of comparing {@link LeaseStatus} themselves: the rule is load-bearing in
 * several places (slot occupancy, the one-active-lease limit, entry permission), and forgetting
 * the time half in any one of them fails silently rather than loudly (research R-03).
 */
public interface BoothLeaseRepository extends JpaRepository<BoothLease, Long> {

    @Query("""
            select l from BoothLease l
            where l.slotId = :slotId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt > :moment
            """)
    Optional<BoothLease> findValidBySlotId(@Param("slotId") Long slotId, @Param("moment") Instant moment);

    @Query("""
            select l from BoothLease l
            where l.lesseeUserId = :userId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt > :moment
            """)
    Optional<BoothLease> findValidByLesseeUserId(@Param("userId") Long userId, @Param("moment") Instant moment);

    /**
     * Locks the slot's {@code ACTIVE} lease row so an early return can decide its fate alone
     * (FR-020). <b>Deliberately has no time predicate</b> — this only takes the lock; validity is
     * then re-read through {@link #findValidBySlotId}, which keeps the expiry rule in the one
     * place this interface promises it lives.
     *
     * <p><b>Keyed on the slot rather than the lessee</b> (S15P21A604-905). An administrator holds a
     * lease per slot, so a lessee-keyed lookup would return several rows and throw where it used to
     * answer. The slot is the narrower key for everyone — one slot has at most one {@code ACTIVE}
     * lease ({@code ux_booth_leases_active_slot}) — and the caller checks the lessee afterwards, so
     * a member's outcome is unchanged: naming a slot they do not hold is still refused.
     *
     * <p>Binding a {@code moment} here could not work: this query <i>is</i> how the lock is taken,
     * so the caller has no instant to pass yet, and a bound parameter would not refresh while the
     * statement waits for the lock.
     *
     * <p><b>It waits rather than skipping.</b> {@link #findStaleActive} takes the same rows with
     * {@code SKIP LOCKED}, so both orderings are defined: if the return locks first the sweeper
     * passes the row over and the return wins; if the sweeper locks first this statement blocks,
     * and PostgreSQL re-evaluates {@code status = ACTIVE} after the lock, so a row the expiry just
     * committed drops out of the result and the return is refused. Skipping here would refuse a
     * return over a sweeper transaction that may still roll back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from BoothLease l
            where l.slotId = :slotId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
            """)
    Optional<BoothLease> findActiveBySlotIdForUpdate(@Param("slotId") Long slotId);

    @Query("""
            select l from BoothLease l
            where l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE and l.endsAt > :moment
            """)
    List<BoothLease> findAllValid(@Param("moment") Instant moment);

    @Query("""
            select l from BoothLease l
            where l.boothId = :boothId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt > :moment
            """)
    Optional<BoothLease> findValidByBoothId(@Param("boothId") Long boothId, @Param("moment") Instant moment);

    /**
     * Leases still marked {@code ACTIVE} whose time has passed, for one slot.
     *
     * <p>These are what block a re-lease: the partial unique index counts them as active even
     * though they are logically over (FR-017).
     */
    @Query("""
            select l from BoothLease l
            where l.slotId = :slotId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt <= :moment
            order by l.id
            """)
    List<BoothLease> findStaleActiveBySlotId(@Param("slotId") Long slotId, @Param("moment") Instant moment);

    /**
     * The member's own leases still marked {@code ACTIVE} whose time has passed.
     *
     * <p>Needed for the same reason as {@link #findStaleActiveBySlotId}: {@code
     * ux_booth_leases_active_lessee} (V6) does not look at {@code ends_at}, so a stale row of the
     * member's own would block every future lease they attempt, on any slot.
     *
     * <p>Ordered by id so that two transactions cleaning up the same rows take them in the same
     * order and queue instead of deadlocking.
     */
    @Query("""
            select l from BoothLease l
            where l.lesseeUserId = :userId and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt <= :moment
            order by l.id
            """)
    List<BoothLease> findStaleActiveByLesseeUserId(@Param("userId") Long userId, @Param("moment") Instant moment);

    /**
     * Every lease still marked {@code ACTIVE} past its end, wherever it sits — the sweeper's query
     * ({@link BoothLeaseExpirySweeper}, S15P21A604-152).
     *
     * <p>The two queries above are scoped to the slot or the member a request is already about. This
     * one has no such scope, which is what makes the row lock and the cap necessary rather than
     * optional.
     *
     * <p>{@code max} keeps one transaction bounded. The ceiling today is the twelve seeded
     * {@code USER_RENTAL} slots — {@code ux_booth_leases_active_slot} allows one {@code ACTIVE}
     * lease per slot — so this is insurance against the slot table growing, not a limit anyone hits
     * now. Rows a pass leaves behind are simply taken by the next one.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: the write lock is what stops two instances from both
     * expiring the same lease, and skipping means the second instance takes different rows instead
     * of waiting for the first to commit. PostgreSQL re-evaluates the predicate after the lock, so
     * even a row that lost the race drops out of the result rather than being expired twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select l from BoothLease l
            where l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE and l.endsAt <= :moment
            order by l.id
            limit :max
            """)
    List<BoothLease> findStaleActive(@Param("moment") Instant moment, @Param("max") int max);

    /**
     * Active leases that crossed the D07 one-hour warning boundary but have not yet claimed it.
     *
     * <p>The lock and {@code SKIP LOCKED} make two application instances divide the work instead
     * of both notifying the same tenant. A late pass still notifies while time remains; a passed
     * end time is excluded because expiry, not a warning, is then the truthful state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select l from BoothLease l
            where l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
              and l.endsAt > :now and l.endsAt <= :warningDeadline
              and l.expiryWarningSentAt is null
            order by l.endsAt, l.id
            limit :max
            """)
    List<BoothLease> findUnwarnedExpiring(@Param("now") Instant now,
                                          @Param("warningDeadline") Instant warningDeadline,
                                          @Param("max") int max);
}
