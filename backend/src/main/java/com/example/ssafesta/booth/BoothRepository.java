package com.example.ssafesta.booth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoothRepository extends JpaRepository<Booth, Long> {

    /**
     * A member keeps one booth across leases, so re-leasing continues their own content rather
     * than handing them someone else's (spec 004 C-01).
     */
    Optional<Booth> findByOwnerUserId(Long ownerUserId);

    Optional<Booth> findByCurrentSlotId(Long currentSlotId);
}
