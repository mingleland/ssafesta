package com.example.ssafesta.internal.ai;

import com.example.ssafesta.ai.AiAgent;
import com.example.ssafesta.ai.AiAgentRepository;
import com.example.ssafesta.project.Project;
import com.example.ssafesta.project.ProjectRepository;
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
    private final ProjectRepository projects;

    AiAgentConfigService(AiAgentRepository agents, ProjectRepository projects) {
        this.agents = agents;
        this.projects = projects;
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
        return AgentConfigView.found(agent, projects.findByBoothId(boothId).orElse(null));
    }

    /**
     * The three shapes of {@code AgentConfigResponse}. {@code NON_NULL} drops the prompt fields on a
     * denial and {@code denialCode} on success, matching the contract's {@code oneOf} branches.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentConfigView(boolean found, String role, String tone, String responseLength,
                                  String systemPrompt, List<String> forbiddenTopics,
                                  ProjectFacts projectFacts, String denialCode) {

        static AgentConfigView found(AiAgent agent, Project project) {
            return new AgentConfigView(true, agent.getRole(), agent.getTone(),
                    agent.getResponseLength(), agent.getSystemPrompt(), agent.getForbiddenTopics(),
                    ProjectFacts.of(project), null);
        }

        static AgentConfigView agentNotInBooth() {
            return new AgentConfigView(false, null, null, null, null, null, null,
                    "AGENT_NOT_IN_BOOTH");
        }

        static AgentConfigView agentInactive() {
            return new AgentConfigView(false, null, null, null, null, null, null, "AGENT_INACTIVE");
        }
    }

    /**
     * rule-based 단축 응답이 쓰는 정형 정보 (S15P21A604-597·-396).
     *
     * <p><b>세 값 모두 {@code null} 일 수 있다.</b> 소개는 운영자가 안 썼을 수 있고, 나머지 둘은 AI 가
     * 아직 추출하지 않았을 수 있다. 그래서 {@code projectFacts} 자체도 프로젝트가 없으면 빠진다 —
     * 값이 없는 것을 빈 문자열로 채우면 받는 쪽이 "소개가 없는 부스" 와 "소개가 빈 문자열인 부스" 를
     * 구분하지 못한다.
     *
     * @param introduction AI 생성 소개를 우선하고, 아직 없으면 운영자가 쓴 소개를 사용한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectFacts(String introduction, String targetAudience, String techStack) {

        static ProjectFacts of(Project project) {
            if (project == null) {
                return null;
            }
            String introduction = project.getAiIntroduction() != null
                    ? project.getAiIntroduction() : project.getDescription();
            return new ProjectFacts(introduction, project.getTargetAudience(),
                    project.getTechStack());
        }
    }
}
