package com.example.ssafesta.booth;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BoothSlotRepository extends JpaRepository<BoothSlot, Long> {

    /** All slots in display order. There are seven of them, so a full read is the cheap path. */
    @Query("select s from BoothSlot s order by s.floorNo asc, s.slotCode asc")
    List<BoothSlot> findAllOrdered();
}
