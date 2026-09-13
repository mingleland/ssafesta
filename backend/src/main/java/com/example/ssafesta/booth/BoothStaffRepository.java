package com.example.ssafesta.booth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read-only membership lookups for {@link BoothAccessGuard}. Writes belong to spec 011 (R-07). */
public interface BoothStaffRepository extends JpaRepository<BoothStaff, BoothStaff.Key> {

    /**
     * The role, not just "is a member" — the guard has to tell a consultant from an editor
     * (spec 011 FR-002, C-09).
     */
    @Query("select s.role from BoothStaff s where s.boothId = :boothId and s.userId = :userId")
    Optional<String> findRole(@Param("boothId") Long boothId, @Param("userId") Long userId);
}
