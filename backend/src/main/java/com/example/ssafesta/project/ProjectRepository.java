package com.example.ssafesta.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <p>{@code findByBoothId} returns {@link Optional} rather than a list, and that is a claim: one
 * project per booth (C-01). {@code ux_projects_booth} (V14) is what makes the claim safe — without
 * it a second row would turn this call into
 * {@code IncorrectResultSizeDataAccessException} and lock the booth out of its own exhibition,
 * which is the failure V7 documented for {@code booths}.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByBoothId(Long boothId);
}
