package com.example.ssafesta.booth;

import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only membership lookups for {@link BoothAccessGuard}. Writes belong to spec 011 (R-07). */
public interface BoothStaffRepository extends JpaRepository<BoothStaff, BoothStaff.Key> {

    boolean existsByBoothIdAndUserId(Long boothId, Long userId);
}
