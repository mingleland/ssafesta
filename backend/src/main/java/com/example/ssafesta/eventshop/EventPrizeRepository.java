package com.example.ssafesta.eventshop;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventPrizeRepository extends JpaRepository<EventPrize, Long> {

    /** The member-facing catalog — inactive prizes never appear here. */
    List<EventPrize> findAllByActiveTrueOrderByIdAsc();

    /** The admin console's full roster, active and inactive alike. */
    List<EventPrize> findAllByOrderByIdAsc();

    /**
     * Locked for the purchase transaction — {@link EventPrize#reserve} must run under this lock or
     * two concurrent purchases can both read enough stock and both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EventPrize p where p.id = :id")
    Optional<EventPrize> findByIdForUpdate(@Param("id") Long id);
}
