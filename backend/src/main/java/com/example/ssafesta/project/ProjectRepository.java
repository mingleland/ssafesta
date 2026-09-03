package com.example.ssafesta.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <p>{@code findByBoothId} returns {@link Optional} rather than a list, and that is a claim: one
 * project per booth (C-01). {@code ux_projects_booth} (V14) is what makes the claim safe — without
 * it a second row would turn this call into
 * {@code IncorrectResultSizeDataAccessException} and lock the booth out of its own exhibition,
 * which is the failure V7 documented for {@code booths}.
 *
 * <p>The four like queries are native because nothing maps {@code project_likes} — 계약 §6 reads two
 * scalars off it and §8 writes one row at a time. Owning an entity for a composite-key table would
 * cost an {@code @IdClass} that still no caller needs: the write path addresses a row by
 * {@code (project_id, user_id)}, which is the primary key itself
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

    /**
     * 계약 §8 의 쓰기 경로. {@code ON CONFLICT DO NOTHING} 이 멱등을 DB 에 맡긴다 — 먼저 읽고
     * 분기하는 길보다 쿼리가 하나 적고, 같은 회원의 두 요청이 겹쳐도 PK 위반이 나지 않는다.
     */
    @Modifying
    @Query(value = "INSERT INTO project_likes(project_id, user_id) VALUES (:projectId, :userId)"
            + " ON CONFLICT DO NOTHING", nativeQuery = true)
    void insertLike(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /** 없는 행을 지우는 것은 오류가 아니다 — 계약 §8 의 {@code DELETE} 는 멱등이다. */
    @Modifying
    @Query(value = "DELETE FROM project_likes WHERE project_id = :projectId AND user_id = :userId",
            nativeQuery = true)
    void deleteLike(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
