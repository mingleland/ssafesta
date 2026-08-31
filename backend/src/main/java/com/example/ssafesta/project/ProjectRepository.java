package com.example.ssafesta.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <p>{@code findByBoothId} returns {@link Optional} rather than a list, and that is a claim: one
 * project per booth (C-01). {@code ux_projects_booth} (V14) is what makes the claim safe — without
 * it a second row would turn this call into
 * {@code IncorrectResultSizeDataAccessException} and lock the booth out of its own exhibition,
 * which is the failure V7 documented for {@code booths}.
 *
 * <p>The two like queries are native because nothing maps {@code project_likes} — its write path is
 * S15P21A604-135, and 계약 §6 only reads two scalars off it. Owning an entity for a composite-key
 * table would cost an {@code @IdClass} that no caller needs yet
 * ({@code AiAgentRepository.hasDocuments} does the same thing for the same reason).
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByBoothId(Long boothId);

    @Query(value = "SELECT COUNT(*) FROM project_likes WHERE project_id = :projectId",
            nativeQuery = true)
    long countLikes(@Param("projectId") Long projectId);

    /**
     * @param userId the viewer, never {@code null} — a guest has no row to match, so the caller
     *        answers {@code false} without asking (계약 §6: 게스트는 {@code likedByMe: false})
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM project_likes WHERE project_id = :projectId"
            + " AND user_id = :userId)", nativeQuery = true)
    boolean isLikedBy(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
