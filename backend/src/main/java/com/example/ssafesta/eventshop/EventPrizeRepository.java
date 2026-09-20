package com.example.ssafesta.eventshop;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    /** Prizes whose deadline has passed but which are still on sale (S15P21A604-922). */
    @Query("select p.id from EventPrize p where p.active = true and p.closesAt is not null"
            + " and p.closesAt <= :now")
    List<Long> findIdsToClose(@Param("now") Instant now);

    /**
     * Raffles that are off sale and have never been drawn.
     *
     * <p>Keyed on {@code drawnAt} rather than {@code active} alone so a prize an administrator
     * closed by hand before its deadline still gets drawn — otherwise its entrants would never
     * learn anything.
     */
    @Query("select p.id from EventPrize p where p.active = false and p.winnerCount > 0"
            + " and p.drawnAt is null")
    List<Long> findIdsToDraw();
}
