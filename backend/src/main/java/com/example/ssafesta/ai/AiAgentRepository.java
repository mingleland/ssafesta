package com.example.ssafesta.ai;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <p>{@code findByBoothId} returns {@link Optional} rather than a list, and that is a claim: one
 * agent per booth (C-13). {@code ux_ai_agents_booth} (V15) is what makes the claim safe.
 *
 * <p>The two {@code exists} queries are native because nothing else in Spring maps those tables —
 * {@code ai_documents} belongs to S15P21A604-106 and {@code consultations} to spec 011. Asking the
 * question does not require owning the entity.
 */
public interface AiAgentRepository extends JpaRepository<AiAgent, Long> {

    Optional<AiAgent> findByBoothId(Long boothId);

    /**
     * Locks the agent row so document-quota checks are not racing each other (spec 007 FR-018).
     *
     * <p>The 10-document and 100MB ceilings are read-then-insert, and the partial unique index only
     * catches two uploads of the <i>same</i> file. Two different files would each read "nine" and
     * each insert. Serialising on the agent row is what makes the count mean something — the same
     * move C-14 makes with the booth row for publish versus delete.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AiAgent a WHERE a.id = :id")
    Optional<AiAgent> findWithLockById(@Param("id") Long id);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM ai_documents WHERE agent_id = :agentId)",
            nativeQuery = true)
    boolean hasDocuments(@Param("agentId") Long agentId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM consultations WHERE agent_id = :agentId)",
            nativeQuery = true)
    boolean hasConsultations(@Param("agentId") Long agentId);
}
