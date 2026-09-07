package com.example.ssafesta.survey;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * One survey per booth (C-06), so the booth is the only lookup anyone needs.
 *
 * <p>{@code ux_surveys_booth} (V22) guarantees the {@code Optional} — without it two concurrent
 * saves would each read "none" and create a row, and this method would start returning whichever
 * one the planner reached first.
 */
public interface SurveyRepository extends JpaRepository<Survey, Long> {

    Optional<Survey> findByBoothId(Long boothId);
}
