package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.mission.DailyMission;
import com.example.ssafesta.mission.DailyMissionMarkerService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: FastAPI reports that a member started talking to an AI agent
 * (spec 022 FR-003a, S15P21A604-955).
 *
 * <p>The conversation never reaches Spring — nginx sends {@code /ai/v1/**} straight to FastAPI — so
 * without this call the {@code AI_CONSULT} mission has no fact to read and stays at 0 forever no
 * matter how long the member talks.
 *
 * <p>{@code userId} in the body is not a claim the browser makes. FastAPI verified the member's JWT
 * before creating the Conversation and forwards the identity it decoded; a user access token does
 * not open {@code /internal/**} at all, so this path cannot be used to award oneself the mission.
 *
 * <p>Contract: {@code specs/008-ai-conversation-rag/contracts/spring-mission-marker-api.yaml}.
 * {@link Hidden} keeps it out of the public {@code /v3/api-docs}, as with every internal endpoint.
 */
@Hidden
@RestController
@RequestMapping("/internal/ai/mission")
class AiMissionMarkerController {

    private final DailyMissionMarkerService markers;

    AiMissionMarkerController(DailyMissionMarkerService markers) {
        this.markers = markers;
    }

    /**
     * Idempotent: the marker is written with {@code setIfAbsent}, so a retried or duplicated report
     * on the same KST day is harmless and the progress stays 1/1.
     */
    @PostMapping(path = "/ai-consult", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAiConsult(@RequestBody(required = false) String body) {
        AiConsultMarkRequest request = StrictJsonReader.read(body, AiConsultMarkRequest.class);
        if (request.userId() == null || request.userId() <= 0) {
            throw ApiException.fieldInvalid("userId", "userId 는 양수여야 합니다.");
        }
        markers.mark(DailyMission.AI_CONSULT, request.userId());
    }

    record AiConsultMarkRequest(Long userId) { }
}
