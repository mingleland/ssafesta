package com.example.ssafesta.survey;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The event-only, administrator-only exception to ordinary survey-result anonymity (742 #59). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminEventSurveyEntrantIntegrationTest {

    private static final String EVENT_KEY = "SSAFESTA_2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private SurveyRepository surveys;
    @Autowired private SurveyResponseRepository responses;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    private Long eventSurveyId;

    @BeforeEach
    void emptyEventResponses() {
        eventSurveyId = surveys.findBySurveyKey(EVENT_KEY).orElseThrow().getId();
        jdbc.update("DELETE FROM survey_answers WHERE response_id IN (SELECT id FROM survey_responses WHERE survey_id=?)",
                eventSurveyId);
        jdbc.update("DELETE FROM survey_responses WHERE survey_id=?", eventSurveyId);
    }

    @Test
    void administratorReadsOnlyEventMemberEntrantsInSubmittedOrder() throws Exception {
        Long earlier = member("이전참여자");
        Long later = member("최근참여자");
        responses.saveAndFlush(SurveyResponse.byMember(eventSurveyId, earlier,
                Instant.parse("2026-09-16T01:00:00Z")));
        responses.saveAndFlush(SurveyResponse.byMember(eventSurveyId, later,
                Instant.parse("2026-09-16T02:00:00Z")));
        Long administrator = administrator("이벤트운영자");

        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/entrants", EVENT_KEY)
                        .header("Authorization", bearer(administrator))
                        .queryParam("page", "0").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(later))
                .andExpect(jsonPath("$.content[0].nickname").value(startsWith("최근참여자")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void ordinaryMemberIsRefusedAndUnknownEventKeyIsNotExposed() throws Exception {
        Long ordinary = member("일반참여자");
        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/entrants", EVENT_KEY)
                        .header("Authorization", bearer(ordinary)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Long administrator = administrator("조회운영자");
        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/entrants", "UNKNOWN_EVENT")
                        .header("Authorization", bearer(administrator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURVEY_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/entrants", EVENT_KEY)
                        .header("Authorization", bearer(administrator))
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listShowsTheSeededEventSurveyWithItsCounts() throws Exception {
        Long earlier = member("목록참여자");
        responses.saveAndFlush(SurveyResponse.byMember(eventSurveyId, earlier,
                Instant.parse("2026-09-16T01:00:00Z")));
        Long administrator = administrator("목록운영자");

        mockMvc.perform(get("/api/v1/admin/event-surveys").header("Authorization", bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.surveyKey == '" + EVENT_KEY + "')]").exists())
                .andExpect(jsonPath("$[?(@.surveyKey == '" + EVENT_KEY + "')].entrantCount").value(1));
    }

    @Test
    void aggregateAndResponseDetailReadOneSubmission() throws Exception {
        Long respondent = member("집계참여자");
        SurveyResponse response = responses.saveAndFlush(SurveyResponse.byMember(eventSurveyId, respondent,
                Instant.parse("2026-09-16T03:00:00Z")));
        Long administrator = administrator("집계운영자");

        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/aggregate", EVENT_KEY)
                        .header("Authorization", bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/responses/{id}", EVENT_KEY, response.getId())
                        .header("Authorization", bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(respondent))
                .andExpect(jsonPath("$.nickname").value(startsWith("집계참여자")));

        mockMvc.perform(get("/api/v1/admin/event-surveys/{key}/responses/{id}", EVENT_KEY, 999999999L)
                        .header("Authorization", bearer(administrator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private Long member(String nickname) {
        return createMemberWithWallet(users, wallets, nickname);
    }

    private Long administrator(String nickname) {
        Long userId = member(nickname);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
