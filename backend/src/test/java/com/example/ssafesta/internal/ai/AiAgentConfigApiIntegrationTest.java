package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /internal/ai/agent-config} — FastAPI 의 프롬프트 빌더가 질문마다 묻는 Agent 설정 조회
 * (spec 008, S15P21A604-399, 계약 {@code spring-agent-config-api.yaml}).
 *
 * <p>거부는 오류가 아니라 {@code 200 + found:false + denialCode} 다 — {@code booth-access} 와 같은
 * 관례다. 401 은 서비스 토큰 실패에만 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiAgentConfigApiIntegrationTest {

    /** {@code src/test/resources/application-local.properties} 가 넣는 값과 같아야 한다. */
    private static final String SERVICE_TOKEN = "test-ai-to-spring";

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    @BeforeEach
    void freeSlots() {
        releaseAllSlots(jdbc);
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Test
    void anActiveAgentOfThatBoothReturnsItsPromptConfig() throws Exception {
        Long boothId = newBooth("정상");
        long agentId = insertAgent(boothId, "ACTIVE", "PROJECT_DOCENT", "FRIENDLY", "MEDIUM",
                "문서를 근거로 답한다.", List.of("가격 협상"));

        mockMvc.perform(agentConfig(boothId, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.role").value("PROJECT_DOCENT"))
                .andExpect(jsonPath("$.tone").value("FRIENDLY"))
                .andExpect(jsonPath("$.responseLength").value("MEDIUM"))
                .andExpect(jsonPath("$.systemPrompt").value("문서를 근거로 답한다."))
                .andExpect(jsonPath("$.forbiddenTopics[0]").value("가격 협상"));

        assertFalse(bodyOf(boothId, agentId).has("denialCode"),
                "성공 응답에 denialCode 가 실리면 안 된다");
    }

    /** 금지 주제를 두지 않은 Agent 는 빈 배열로 답한다 — {@code null} 이 아니다. */
    @Test
    void noForbiddenTopicsIsAnEmptyArrayNotNull() throws Exception {
        Long boothId = newBooth("빈금지주제");
        long agentId = insertAgent(boothId, "ACTIVE", "GUIDE", "PROFESSIONAL", "SHORT",
                "안내한다.", null);

        mockMvc.perform(agentConfig(boothId, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forbiddenTopics").isArray())
                .andExpect(jsonPath("$.forbiddenTopics.length()").value(0));
    }

    /** 없는 직원은 남의 booth 소속인지 존재하지 않는지 구분해 알려주지 않는다. */
    @Test
    void anUnknownAgentIsRefusedAsNotInBooth() throws Exception {
        Long boothId = newBooth("없는직원");

        JsonNode body = bodyOf(boothId, 9_999_997L);

        assertFalse(body.get("found").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("AGENT_NOT_IN_BOOTH",
                body.get("denialCode").asString());
    }

    /** 다른 부스의 직원을 이 부스 이름으로 물어도 마찬가지다 (헌법 17조 격리). */
    @Test
    void anAgentOfAnotherBoothIsRefusedAsNotInBooth() throws Exception {
        Long mine = newBooth("내부스");
        Long other = newBooth("남부스");
        long strangerAgent = insertAgent(other, "ACTIVE", "GUIDE", "FRIENDLY", "MEDIUM", "안내한다.",
                null);

        mockMvc.perform(agentConfig(mine, strangerAgent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.denialCode").value("AGENT_NOT_IN_BOOTH"))
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    /**
     * 저장값이 {@code ACTIVE} 가 아니면 전부 거부한다 — {@code AiAgent.isActive()} 와 같은 정규화다.
     *
     * <p>거부 응답에는 프롬프트 필드를 싣지 않는다. INACTIVE 인 Agent 의 {@code systemPrompt} 를
     * FastAPI 에 흘려 봐야 쓸 수 없는 값이고, 계약의 {@code additionalProperties: false} 분기와도
     * 어긋난다.
     */
    @Test
    void aDisabledAgentIsRefusedWithoutLeakingItsPromptFields() throws Exception {
        Long boothId = newBooth("비활성");
        long agentId = insertAgent(boothId, "DISABLED", "GUIDE", "FRIENDLY", "MEDIUM",
                "비밀 프롬프트", null);

        mockMvc.perform(agentConfig(boothId, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.denialCode").value("AGENT_INACTIVE"))
                .andExpect(jsonPath("$.systemPrompt").doesNotExist());
    }

    // ── 인증 ────────────────────────────────────────────────────────────────

    @Test
    void aMissingServiceTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/agent-config").param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aWrongServiceTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/agent-config")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN + "-tampered")
                        .param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /** 반대 방향 토큰은 통하지 않는다 (GitLab #102). */
    @Test
    void theOppositeDirectionTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/agent-config")
                        .header("Authorization", "Bearer test-spring-to-ai")
                        .param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /** 사용자 Access Token 으로는 내부 경로가 열리지 않는다. */
    @Test
    void aUserAccessTokenDoesNotOpenTheInternalPath() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "유저토큰");
        Long boothId = booths.save(new Booth(userId, "유저토큰 부스")).getId();

        mockMvc.perform(get("/internal/ai/agent-config")
                        .header("Authorization", "Bearer " + sessions.issue(userId).accessToken())
                        .param("boothId", String.valueOf(boothId))
                        .param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 내부 endpoint 가 공개 OpenAPI 문서에 실리지 않는지 — {@code AiBoothAccessApiIntegrationTest}
     * 와 같은 이유로 이 컨트롤러에서도 직접 확인한다({@code @Hidden} 은 컨트롤러마다 붙는다).
     */
    @Test
    void theInternalPathIsNotPublishedInThePublicApiDocument() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> published = new ArrayList<>();
        for (String path : jsonMapper.readTree(document).path("paths").propertyNames()) {
            if (path.startsWith("/internal")) {
                published.add(path);
            }
        }
        assertFalse(!published.isEmpty(), "내부 경로가 공개 OpenAPI 문서에 실렸다: " + published);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private JsonNode bodyOf(Long boothId, long agentId) throws Exception {
        return jsonMapper.readTree(mockMvc.perform(agentConfig(boothId, agentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private RequestBuilder agentConfig(Long boothId, long agentId) {
        return get("/internal/ai/agent-config")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .param("boothId", String.valueOf(boothId))
                .param("agentId", String.valueOf(agentId));
    }

    private Long newBooth(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        return booths.save(new Booth(userId, prefix + " 부스")).getId();
    }

    /** 상태·필드를 직접 정해야 해서 서비스가 아니라 SQL 로 심는다. */
    private long insertAgent(Long boothId, String status, String role, String tone,
                             String responseLength, String systemPrompt,
                             List<String> forbiddenTopics) {
        String topicsJson = forbiddenTopics == null ? null : toJsonArray(forbiddenTopics);
        return jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt,
                    response_length, forbidden_topics, status)
                VALUES (?, '도슨트', ?, ?, ?, ?, CAST(? AS jsonb), ?)
                RETURNING id
                """, Long.class, boothId, role, tone, systemPrompt, responseLength, topicsJson,
                status);
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }
}
