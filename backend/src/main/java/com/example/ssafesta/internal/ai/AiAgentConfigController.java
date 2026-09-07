package com.example.ssafesta.internal.ai;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server only: FastAPI's prompt builder calls this once per question (spec 008,
 * S15P21A604-399, GitLab #119 §5).
 *
 * <p>Same prefix and therefore the same chain as {@link AiBoothAccessController} —
 * {@link AiInternalSecurityConfiguration} already matches {@code /internal/ai/**} and requires the
 * FastAPI→Spring service token, so this endpoint adds no security configuration of its own.
 *
 * <p>{@link Hidden} keeps it out of {@code /v3/api-docs}, which is public. Its contract lives in
 * {@code specs/008-ai-conversation-rag/contracts/spring-agent-config-api.yaml}.
 */
@Hidden
@RestController
@RequestMapping("/internal/ai")
class AiAgentConfigController {

    private final AiAgentConfigService config;

    AiAgentConfigController(AiAgentConfigService config) {
        this.config = config;
    }

    @GetMapping("/agent-config")
    AiAgentConfigService.AgentConfigView agentConfig(@RequestParam Long boothId,
                                                     @RequestParam Long agentId) {
        return config.find(boothId, agentId);
    }
}
