package com.example.ssafesta.internal.ai;

import com.example.ssafesta.ai.AiAgent;
import com.example.ssafesta.ai.AiAgentRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what should this agent say?" for FastAPI's prompt builder (spec 008, S15P21A604-399,
 * contract {@code spring-agent-config-api.yaml}). Spring owns {@code ai_agents}; FastAPI only
 * interprets the vocabulary into a prompt (C-12) and never queries Business DB directly (#119).
 *
 * <p>Same two checks and the same shape of answer as {@link AiBoothAccessService}: booth
 * membership, then {@code ACTIVE} status, short-circuiting in that order. A refusal is {@code 200}
 * + {@code denialCode}, not a 4xx — the caller learns it may not use this config, not whether the
 * row exists.
 */
@Service
public class AiAgentConfigService {

    private final AiAgentRepository agents;

    AiAgentConfigService(AiAgentRepository agents) {
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public AgentConfigView find(Long boothId, Long agentId) {
        AiAgent agent = agents.findById(agentId)
                .filter(found -> boothId.equals(found.getBoothId()))
                .orElse(null);
        if (agent == null) {
            // 존재하지 않는 agentId 와 남의 booth 소속 agentId 를 구분해 알려주지 않는다 —
            // booth-access 와 같은 이유다.
            return AgentConfigView.agentNotInBooth();
        }
        if (!agent.isActive()) {
            return AgentConfigView.agentInactive();
        }
        return AgentConfigView.found(agent);
    }

    /**
     * The three shapes of {@code AgentConfigResponse}. {@code NON_NULL} drops the prompt fields on a
     * denial and {@code denialCode} on success, matching the contract's {@code oneOf} branches.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentConfigView(boolean found, String role, String tone, String responseLength,
                                  String systemPrompt, List<String> forbiddenTopics,
                                  String denialCode) {

        static AgentConfigView found(AiAgent agent) {
            return new AgentConfigView(true, agent.getRole(), agent.getTone(),
                    agent.getResponseLength(), agent.getSystemPrompt(), agent.getForbiddenTopics(),
                    null);
        }

        static AgentConfigView agentNotInBooth() {
            return new AgentConfigView(false, null, null, null, null, null, "AGENT_NOT_IN_BOOTH");
        }

        static AgentConfigView agentInactive() {
            return new AgentConfigView(false, null, null, null, null, null, "AGENT_INACTIVE");
        }
    }
}
