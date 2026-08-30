package com.example.ssafesta.ai;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI staff configuration — the owner-facing half of spec 007 (docs/08 §6).
 *
 * <p>Additive: five new endpoints, no existing consumer changes. {@code DELETE} was not in the
 * canonical spec; it came from the Jira completion criteria and was confirmed 2026-08-30, so it is
 * written into spec 007 C-14 alongside the reference rules it needs.
 */
@RestController
@RequestMapping("/api/v1")
public class AiAgentController {

    /** Guests own nothing (헌법 12조), and saying so at the door beats failing later. */
    private static final String MEMBER_ONLY = "회원 계정만 AI 직원을 편집할 수 있습니다.";

    private final AiAgentService agents;

    public AiAgentController(AiAgentService agents) {
        this.agents = agents;
    }

    @PostMapping("/booths/{boothId}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView create(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable Long boothId,
                                           @RequestBody(required = false)
                                           AiAgentService.AgentCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.create(boothId, userId, command);
    }

    /** 0개 또는 1개 배열. 아직 안 만든 것은 오류가 아니다. */
    @GetMapping("/booths/{boothId}/agents")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentListView list(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long boothId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return new AiAgentService.AgentListView(agents.findByBooth(boothId, userId));
    }

    @GetMapping("/agents/{agentId}")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView one(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable Long agentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.findOne(agentId, userId);
    }

    @PatchMapping("/agents/{agentId}")
    @SecurityRequirement(name = "bearerAuth")
    public AiAgentService.AgentView update(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable Long agentId,
                                           @RequestBody(required = false)
                                           AiAgentService.AgentCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return agents.update(agentId, userId, command);
    }

    @DeleteMapping("/agents/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long agentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        agents.delete(agentId, userId);
    }
}
