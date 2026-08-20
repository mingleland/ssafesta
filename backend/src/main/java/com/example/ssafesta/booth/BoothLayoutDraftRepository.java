package com.example.ssafesta.booth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoothLayoutDraftRepository extends JpaRepository<BoothLayoutDraft, Long> {

    /**
     * Saves only if nobody else has saved since the client read the draft (FR-014, invariant I-6).
     *
     * <p>The check and the write are <b>one statement</b>, so there is no window between them.
     * Reading the revision first and comparing in Java would be a check-then-act — the same shape
     * that let one member lease seven booths at once in T-110.
     *
     * @return rows updated: {@code 1} on success, {@code 0} when the revision moved on
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BoothLayoutDraft d
               SET d.schemaVersion = :schemaVersion,
                   d.layoutJson = :layoutJson,
                   d.revision = d.revision + 1,
                   d.updatedByUserId = :userId,
                   d.updatedAt = :now
             WHERE d.boothId = :boothId
               AND d.revision = :expectedRevision
            """)
    int updateIfRevisionMatches(@Param("boothId") Long boothId,
                                @Param("expectedRevision") long expectedRevision,
                                @Param("schemaVersion") int schemaVersion,
                                @Param("layoutJson") String layoutJson,
                                @Param("userId") Long userId,
                                @Param("now") Instant now);
}
