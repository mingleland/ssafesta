package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
            """)
    List<BoothLease> findStaleActiveBySlotId(@Param("slotId") Long slotId, @Param("moment") Instant moment);
}
