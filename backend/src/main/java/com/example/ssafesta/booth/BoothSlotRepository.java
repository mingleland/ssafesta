package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoothSlotRepository extends JpaRepository<BoothSlot, Long> {

    /** All slots in display order. There are twelve of them, so a full read is the cheap path. */
    @Query("select s from BoothSlot s order by s.floorNo asc, s.slotCode asc")
    List<BoothSlot> findAllOrdered();

    /**
     * Slots with their current occupant and that occupant's booth, in one statement
     * (S15P21A604-682).
     *
     * <p>The validity predicate lives in the join rather than in a separate query on purpose: it is
     * the same {@code ACTIVE and endsAt > now} that {@link BoothLeaseRepository#findAllValid} uses,
     * and putting it here is what lets the booth come back in the same snapshot. Splitting them is
     * how a withdrawal slipped between the two reads and produced an {@code OCCUPIED} row pointing
     * at a booth that no longer existed.
     */
    @Query("""
            select new com.example.ssafesta.booth.SlotOccupancy(s, l, b)
            from BoothSlot s
            left join BoothLease l
                on l.slotId = s.id
               and l.status = com.example.ssafesta.booth.LeaseStatus.ACTIVE
               and l.endsAt > :moment
            left join Booth b on b.id = l.boothId
            order by s.floorNo asc, s.slotCode asc
            """)
    List<SlotOccupancy> findOccupancy(@Param("moment") Instant moment);
}
