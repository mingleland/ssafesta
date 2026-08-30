package com.example.ssafesta.ai;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query(value = "SELECT EXISTS(SELECT 1 FROM ai_documents WHERE agent_id = :agentId)",
            nativeQuery = true)
    boolean hasDocuments(@Param("agentId") Long agentId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM consultations WHERE agent_id = :agentId)",
            nativeQuery = true)
    boolean hasConsultations(@Param("agentId") Long agentId);
}
