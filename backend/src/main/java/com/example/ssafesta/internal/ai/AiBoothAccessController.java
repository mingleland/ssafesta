package com.example.ssafesta.internal.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: FastAPI calls this before creating a Conversation (spec 008 FR-024).
 *
 * <p>Not under {@code /api/v1}. The prefix is what {@link AiInternalSecurityConfiguration} matches,
 * and it is the boundary infra keeps off the public listener — a user access token does not open it.
 *
 * <p>Deliberately absent from the OpenAPI document served to browsers: it is not part of the client
 * API, and its contract lives in {@code specs/008/contracts/spring-booth-access-api.yaml}.
 */
@RestController
@RequestMapping("/internal/ai")
class AiBoothAccessController {

    private final AiBoothAccessService access;

    AiBoothAccessController(AiBoothAccessService access) {
        this.access = access;
    }

    @GetMapping("/booth-access")
    AiBoothAccessService.BoothAccessView boothAccess(@RequestParam Long boothId,
                                                     @RequestParam Long agentId) {
        return access.evaluate(boothId, agentId);
    }
}
