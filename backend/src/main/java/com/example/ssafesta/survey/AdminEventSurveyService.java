package com.example.ssafesta.survey;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AdminGuard;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplies the identifying event entrant view that ordinary survey result paths must never expose. */
@Service
public class AdminEventSurveyService {

    private final AdminGuard admins;
    private final SurveyRepository surveys;
    private final SurveyResponseRepository responses;

    public AdminEventSurveyService(AdminGuard admins, SurveyRepository surveys,
                                   SurveyResponseRepository responses) {
        this.admins = admins;
        this.surveys = surveys;
        this.responses = responses;
    }

    @Transactional(readOnly = true)
    public Page<EventEntrantView> entrants(Long actorUserId, String surveyKey, Pageable pageable) {
        admins.requireAdmin(actorUserId);
        Survey event = surveys.findBySurveyKey(surveyKey)
                .orElseThrow(() -> new ApiException(ErrorCode.SURVEY_NOT_FOUND));
        return responses.findEventEntrants(event.getId(), pageable)
                .map(row -> new EventEntrantView(row.getResponseId(), row.getUserId(), row.getNickname(),
                        row.getSubmittedAt()));
    }

    /** Identity is intentionally available only from the admin event-operation endpoint. */
    public record EventEntrantView(Long responseId, Long userId, String nickname, Instant submittedAt) { }
}
