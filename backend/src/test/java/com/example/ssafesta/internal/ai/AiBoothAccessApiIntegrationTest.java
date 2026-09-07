package com.example.ssafesta.internal.ai;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.expireLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /internal/ai/booth-access} — FastAPI 가 Conversation 을 만들기 전에 묻는 검증
 * (spec 008 FR-024·C-08, 계약 {@code spring-booth-access-api.yaml}).
 *
 * <p>거부는 오류가 아니라 {@code 200 + allowed:false + denialCode} 다. 401 은 서비스 토큰 실패에만.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiBoothAccessApiIntegrationTest {

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

    // ── 판정 ────────────────────────────────────────────────────────────────

    @Test
    void aValidLeaseWithAnActiveAgentOfThatBoothIsAllowed() throws Exception {
        Owner owner = leasedOwner("허용");
        long agentId = insertAgent(owner.boothId(), "ACTIVE");

        mockMvc.perform(access(owner.boothId(), agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.boothId").value(owner.boothId()))
                .andExpect(jsonPath("$.agentId").value((int) agentId))
                .andExpect(jsonPath("$.agentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.remainingSeconds").isNumber());

        JsonNode body = bodyOf(owner.boothId(), agentId);
        assertTrue(body.has("leaseEndsAt") && !body.get("leaseEndsAt").isNull(),
                "허용 응답은 leaseEndsAt 이 있어야 한다");
        assertFalse(body.has("denialCode"), "허용 응답에 denialCode 가 실리면 안 된다");
    }

    /**
     * 만료 임대는 종료 시각을 <b>싣지 않는다.</b>
     *
     * <p>만료 임대를 조회하는 경로 자체를 만들지 않았기 때문이다 — 과거 임대가 여럿일 때 어느
     * 행인지 정할 근거가 없고, FastAPI 가 저장하는 값은 성공 응답의 종료 시각뿐이다(FR-024).
     * 그래서 {@code leaseEndsAt == null} 과 {@code BOOTH_LEASE_EXPIRED} 가 정확히 같은 뜻이 된다.
     */
    @Test
    void anExpiredLeaseIsRefusedWithoutALeaseEndsAt() throws Exception {
        Owner owner = leasedOwner("만료");
        long agentId = insertAgent(owner.boothId(), "ACTIVE");
        expireLease(jdbc, owner.boothId());

        mockMvc.perform(access(owner.boothId(), agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.denialCode").value("BOOTH_LEASE_EXPIRED"))
                .andExpect(jsonPath("$.remainingSeconds").value(0));

        JsonNode body = bodyOf(owner.boothId(), agentId);
        assertTrue(body.has("leaseEndsAt") && body.get("leaseEndsAt").isNull(),
                "키는 있고 값이 null 이어야 한다");
        assertFalse(body.has("agentStatus"), "임대에서 단락되므로 agentStatus 는 없어야 한다");
    }

    /** 없는 부스도 404 가 아니라 첫 판정으로 답한다 — 존재 여부를 알려 주지 않는다. */
    @Test
    void anUnknownBoothIsRefusedByTheFirstCheckRatherThanNotFound() throws Exception {
        mockMvc.perform(access(9_999_999L, 9_999_998L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.denialCode").value("BOOTH_LEASE_EXPIRED"));
    }

    /** 없는 직원도 같다 — 임대는 유효하므로 두 번째 판정에서 걸린다. */
    @Test
    void anUnknownAgentIsRefusedAsNotInBooth() throws Exception {
        Owner owner = leasedOwner("없는직원");

        JsonNode body = bodyOf(owner.boothId(), 9_999_997L);

        assertFalse(body.get("allowed").asBoolean());
        assertEquals("AGENT_NOT_IN_BOOTH", body.get("denialCode").asString());
        assertFalse(body.has("agentStatus"), "찾지 못했으므로 agentStatus 는 없어야 한다");
        assertFalse(body.get("leaseEndsAt").isNull(), "임대는 유효하므로 종료 시각은 실린다");
    }

    /** 다른 부스의 직원을 이 부스 이름으로 물어도 마찬가지다 (헌법 17조 격리). */
    @Test
    void anAgentOfAnotherBoothIsRefusedAsNotInBooth() throws Exception {
        Owner mine = leasedOwner("내부스");
        Owner other = leasedOwner("남부스");
        long strangerAgent = insertAgent(other.boothId(), "ACTIVE");

        JsonNode body = bodyOf(mine.boothId(), strangerAgent);

        assertFalse(body.get("allowed").asBoolean());
        assertEquals("AGENT_NOT_IN_BOOTH", body.get("denialCode").asString());
        assertFalse(body.has("agentStatus"));
    }

    /**
     * 저장값이 {@code ACTIVE} 가 아니면 전부 {@code INACTIVE} 로 정규화한다.
     *
     * <p>계약 enum 은 두 값뿐인데 {@code BoothLayoutConfigLinkIntegrationTest} 가 실제로
     * {@code DISABLED} 를 쓴다. 저장값을 그대로 실으면 계약을 깬다.
     */
    @Test
    void aDisabledAgentIsReportedAsInactive() throws Exception {
        Owner owner = leasedOwner("비활성");
        long agentId = insertAgent(owner.boothId(), "DISABLED");

        mockMvc.perform(access(owner.boothId(), agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.denialCode").value("AGENT_INACTIVE"))
                .andExpect(jsonPath("$.agentStatus").value("INACTIVE"));

        assertFalse(bodyOf(owner.boothId(), agentId).get("leaseEndsAt").isNull(),
                "임대는 유효하므로 종료 시각은 실린다");
    }

    /**
     * 한 요청은 시각을 <b>한 번만</b> 읽는다.
     *
     * <p>{@code serverTime}·유효성 판정·잔여초를 각각 {@code Instant.now()} 로 구하면 셋이 서로
     * 어긋난 시점을 가리키고, FastAPI 는 그 응답으로 이후 모든 질문의 만료를 판정한다(FR-024).
     * 잔여초가 두 시각의 차이와 정확히 같은지로 고정한다.
     */
    @Test
    void oneRequestUsesASingleMoment() throws Exception {
        Owner owner = leasedOwner("한시각");
        long agentId = insertAgent(owner.boothId(), "ACTIVE");

        JsonNode body = bodyOf(owner.boothId(), agentId);

        long expected = Duration.between(Instant.parse(body.get("serverTime").asString()),
                Instant.parse(body.get("leaseEndsAt").asString())).toSeconds();
        assertEquals(expected, body.get("remainingSeconds").asLong(),
                "remainingSeconds 가 leaseEndsAt - serverTime 과 달라 시각을 여러 번 읽었다");
    }

    // ── 인증 ────────────────────────────────────────────────────────────────

    @Test
    void aMissingServiceTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/booth-access").param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aWrongServiceTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/booth-access")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN + "-tampered")
                        .param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 반대 방향 토큰은 통하지 않는다 (GitLab #102).
     *
     * <p>두 토큰의 노출 표면이 다르다 — {@code AI_TO_SPRING} 은 사용자 PDF 를 파싱하는 Worker 와
     * 같은 메모리에 있다. 한쪽이 새도 반대 방향이 열리지 않아야 분리한 의미가 있다.
     */
    @Test
    void theOppositeDirectionTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/ai/booth-access")
                        .header("Authorization", "Bearer test-spring-to-ai")
                        .param("boothId", "1").param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /** 사용자 Access Token 으로는 내부 경로가 열리지 않는다. */
    @Test
    void aUserAccessTokenDoesNotOpenTheInternalPath() throws Exception {
        Owner owner = leasedOwner("유저토큰");

        mockMvc.perform(get("/internal/ai/booth-access")
                        .header("Authorization", "Bearer " + sessions.issue(owner.userId()).accessToken())
                        .param("boothId", String.valueOf(owner.boothId()))
                        .param("agentId", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 유효한 서비스 토큰이라도 정의되지 않은 내부 경로는 열리지 않는다.
     *
     * <p>체인이 {@code /internal/**} 를 전부 소비하고 {@code denyAll} 로 닫으므로, 나중에 다른
     * 토큰으로 인증할 경로(Infra reconciliation, spec 007 T078)가 이 토큰으로 새지 않는다.
     */
    @Test
    void anUndefinedInternalPathIsDeniedEvenWithAValidToken() throws Exception {
        mockMvc.perform(get("/internal/storage/anything")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * 내부 endpoint 가 <b>공개 OpenAPI 문서에 실리지 않는지</b>.
     *
     * <p>{@code /v3/api-docs} 는 누구나 열 수 있다({@code SecurityConfiguration} 이 Swagger UI 를
     * 위해 permitAll 한다). springdoc 은 모든 {@code @RestController} 를 훑으므로 {@code @Hidden}
     * 이 없으면 이 경로와 그것이 요구하는 토큰 헤더의 모양이 그대로 공개된다.
     *
     * <p>주석으로 "문서에 없다"고 적는 것만으로는 아무것도 막지 못한다 — 그렇게 적어 두고 코드가
     * 하지 않던 것이 T-99 였다. 여기서 실제 문서를 읽어 확인한다.
     *
     * <p><b>{@code paths} 의 키를 본다</b> — 문서 전체에서 문자열을 찾지 않는다. 초판은
     * {@code document.contains("/internal/")} 였고, S15P21A604-390 이 {@code info.description} 에
     * "{@code /internal/**} 은 이 문서에 나오지 않는다"는 설명을 적자 그 문장 때문에 실패했다.
     * 경로가 실렸는지와 산문이 그 접두사를 언급했는지는 다른 사실이고, 막아야 하는 것은 앞의 것이다.
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
        assertTrue(published.isEmpty(), "내부 경로가 공개 OpenAPI 문서에 실렸다: " + published);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private JsonNode bodyOf(Long boothId, long agentId) throws Exception {
        return jsonMapper.readTree(mockMvc.perform(access(boothId, agentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.RequestBuilder access(Long boothId, long agentId) {
        return get("/internal/ai/booth-access")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .param("boothId", String.valueOf(boothId))
                .param("agentId", String.valueOf(agentId));
    }

    /** 상태를 직접 정해야 해서 서비스가 아니라 SQL 로 심는다 — 서비스는 ACTIVE 만 만든다. */
    private long insertAgent(Long boothId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt, status)
                VALUES (?, '도슨트', 'PROJECT_DOCENT', 'FRIENDLY', '문서를 근거로 답한다.', ?)
                RETURNING id
                """, Long.class, boothId, status);
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
