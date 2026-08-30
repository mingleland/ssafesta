package com.example.ssafesta.internal.ai;

import io.swagger.v3.oas.annotations.Hidden;
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
 * <p>{@link Hidden} keeps it out of {@code /v3/api-docs}, which is public (SecurityConfiguration
 * permits it so Swagger UI works). springdoc scans every {@code @RestController}, so without the
 * annotation this internal endpoint — and the shape of the token header it expects — would be
 * published to anyone who opens the docs. It is not part of the client API; its contract lives in
 * {@code specs/008-ai-conversation-rag/contracts/spring-booth-access-api.yaml}.
 */
@Hidden
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
