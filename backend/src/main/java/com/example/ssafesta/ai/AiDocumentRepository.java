package com.example.ssafesta.ai;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <p>"Active" everywhere below means {@code QUEUED}, {@code PROCESSING} or {@code READY} — the same
 * three states the duplicate rule and the quota use (FR-018, FR-019b). {@code FAILED},
 * {@code DISABLED} and {@code EXPIRED} are excluded on purpose: a failed or abandoned upload must
 * not consume one of the ten slots, and it must not block re-uploading the same file.
 */
interface AiDocumentRepository extends JpaRepository<AiDocument, Long> {

    @Query("SELECT d FROM AiDocument d WHERE d.agentId = :agentId AND d.contentSha256 = :sha "
            + "AND d.processingStatus IN ('QUEUED', 'PROCESSING', 'READY')")
    Optional<AiDocument> findActiveByAgentAndSha(@Param("agentId") Long agentId,
                                                 @Param("sha") String sha);

    @Query("SELECT COUNT(d) FROM AiDocument d WHERE d.agentId = :agentId "
            + "AND d.processingStatus IN ('QUEUED', 'PROCESSING', 'READY')")
    long countActive(@Param("agentId") Long agentId);

    /** {@code COALESCE} because an agent with no documents sums to {@code null}, not zero. */
    @Query("SELECT COALESCE(SUM(d.sizeBytes), 0) FROM AiDocument d WHERE d.agentId = :agentId "
            + "AND d.processingStatus IN ('QUEUED', 'PROCESSING', 'READY')")
    long sumActiveBytes(@Param("agentId") Long agentId);

    /**
     * Locks the row before a state transition.
     *
     * <p>{@code /complete} reads, asks storage, then writes — and the expiry sweeper
     * (S15P21A604-174) may commit {@code QUEUED → EXPIRED} in between. Re-reading under this lock is
     * what stops a stale snapshot from writing the sweeper's decision away.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM AiDocument d WHERE d.id = :id")
    Optional<AiDocument> findWithLockById(@Param("id") Long id);
}
