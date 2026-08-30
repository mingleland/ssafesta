package com.example.ssafesta.booth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface BoothRepository extends JpaRepository<Booth, Long> {

    /**
     * Serialises the operations that must not interleave on one booth.
     *
     * <p>Publishing a layout and deleting an AI agent both read "is this agent referenced?" and then
     * act on the answer. Nothing in the database links them — the reference lives inside the layout
     * JSON, so there is no foreign key to catch the loser of a race, and a publish that validated an
     * agent a moment before it was deleted would leave a public booth pointing at nothing
     * (spec 007 C-14, data-model invariant A-3).
     *
     * <p>The booth row is the natural thing to lock: both operations are scoped to one booth, and
     * nothing else contends for it at publish frequency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booth> findWithLockById(Long id);

    /**
     * A member keeps one booth across leases, so re-leasing continues their own content rather
     * than handing them someone else's (spec 004 C-01).
     */
    Optional<Booth> findByOwnerUserId(Long ownerUserId);

    Optional<Booth> findByCurrentSlotId(Long currentSlotId);
}
