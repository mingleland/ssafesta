package com.example.ssafesta.ai;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * The agent's documents, newest first — every status, including the ones the quota excludes.
     *
     * <p>A list that hid {@code EXPIRED} and {@code FAILED} would answer "where did my upload go?"
     * with silence. US2 scenario 2 asks the opposite: the owner is supposed to see 업로드 만료 and
     * 실패 as states, not as absences.
     */
    List<AiDocument> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    /**
     * Expires grants that were issued and never used (FR-026).
     *
     * <p><b>{@code uploaded_at IS NULL} is a concurrency guard, not a restatement of
     * {@code QUEUED}.</b> Written as status and age alone, this statement is still correct when it
     * is planned and wrong when it runs: {@code /complete} may hold the row, set {@code uploaded_at}
     * and commit while this waits, and PostgreSQL re-evaluates the predicate on wake-up. With the
     * term, the re-check fails and nothing happens; without it, the sweep expires a document that
     * finished a millisecond earlier — the user is told {@code QUEUED}, the row says
     * {@code EXPIRED}, and nothing raises.
     *
     * <p>{@code updated_at} is set by hand because {@code DEFAULT CURRENT_TIMESTAMP} fires on insert
     * only and a bulk update runs no entity callback — this codebase has no {@code @PreUpdate}, so
     * every other writer sets it in {@link AiDocument} itself. One instant for both columns: they
     * record one event.
     *
     * <p>No batch cap and no {@code SKIP LOCKED}. There is no per-row follow-on work — a row this
     * pass misses is simply swept by the next one — so claiming twice costs nothing, which is what
     * makes those devices worth their native query elsewhere ({@code AiDocumentJobRepository}) and
     * not here.
     *
     * <p>ponytail: no index matches this predicate, so the pass is a sequential scan of
     * {@code ai_documents} every five minutes. Fine at the sizes this table holds today (grants are
     * bounded per agent by FR-018). If the scan or the lock hold shows up, add a partial index on
     * the predicate itself — {@code (created_at) WHERE processing_status = 'QUEUED' AND uploaded_at
     * IS NULL} — before reaching for a batched sweep.
     */
    @Modifying
    @Query("UPDATE AiDocument d SET d.processingStatus = 'EXPIRED', d.expiredAt = :now, "
            + "d.updatedAt = :now WHERE d.processingStatus = 'QUEUED' AND d.uploadedAt IS NULL "
            + "AND d.createdAt <= :cutoff")
    int expireAbandonedGrants(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
