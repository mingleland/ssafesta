package com.example.ssafesta.ai;

import com.example.ssafesta.storage.FakeObjectStorage;
import com.example.ssafesta.storage.FakeObjectStorageConfiguration;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static com.example.ssafesta.booth.BoothTestSupport.releaseAllSlots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영자가 업로드를 막았을 때 (spec 007 FR-031, GitLab #100).
 *
 * <p>사유가 <b>다른 코드로</b> 나가는지가 핵심이다. 할당량 초과는 재시도해도 안 되고 장애는 몇 분
 * 뒤 된다 — 한 코드로 뭉치면 FE 가 안내를 가르지 못하고 사용자는 계속 재시도한다.
 *
 * <p>게이트는 한 칸이지만 <b>값이 사유를 말한다</b>(#100, 2026-09-01): {@code QUOTA_BLOCKED} 는
 * 507, {@code UNAVAILABLE} 은 503 이다. boolean 으로 되돌리면 그 구분이 사라진다.
 *
 * <p>설정이 클래스 단위라 사유마다 컨텍스트가 따로 뜬다. 그래서 본체 테스트와 파일을 나눴다.
 */
class AiDocumentUploadBlockedIntegrationTest {

    private static final String AGENT = """
            {"name": "도슨트", "role": "PROJECT_DOCENT", "systemPrompt": "문서를 근거로 답한다."}""";
    private static final String UPLOAD = """
            {"fileName": "project.pdf", "contentType": "application/pdf", "size": 1048576,
             "contentSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}""";

    /** 할당량이 찼다 — usage guard 90% (#100). 기다린다고 풀리지 않으므로 507 이다. */
    @Nested
    @Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
    @SpringBootTest(properties = "app.ai.storage.upload-gate=QUOTA_BLOCKED")
    @AutoConfigureMockMvc
    class WhenTheQuotaIsSpent extends Fixture {

        @Test
        void theGrantIsRefusedAsInsufficientStorageAndNoRowIsCreated() throws Exception {
            long agentId = agent("할당량");

            mockMvc.perform(uploadUrl(agentId))
                    .andExpect(status().isInsufficientStorage())
                    .andExpect(jsonPath("$.code").value("STORAGE_QUOTA_EXCEEDED"));

            // 차단 중 만든 행은 FR-018 의 10개 슬롯을 뒤에 올 객체 없이 먹는다.
            assertEquals(0, documents.countActive(agentId), "차단인데 행이 생겼다");
        }
    }

    /**
     * 감시 불능·R2 장애·전환 창 — 운영자가 전부 {@code UNAVAILABLE} 로 옮겨 적는다. 잠시 뒤 된다: 503.
     *
     * <p>같은 값 하나가 옛 두 축의 {@code STALE_BLOCKED}·{@code UPLOAD_BLOCKED}·
     * {@code FALLBACK_VALIDATING}·{@code R2_RECONCILING} 를 모두 대신한다. 서버는 어느 상태였는지
     * 알 필요가 없고, 알아야 할 것은 "기다리면 되는가" 뿐이다.
     */
    @Nested
    @Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
    @SpringBootTest(properties = "app.ai.storage.upload-gate=UNAVAILABLE")
    @AutoConfigureMockMvc
    class WhenTheProviderCannotBeUsed extends Fixture {

        @Test
        void theGrantIsRefusedAsUnavailableAndNoRowIsCreated() throws Exception {
            long agentId = agent("사용불가");

            mockMvc.perform(uploadUrl(agentId))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));

            assertEquals(0, documents.countActive(agentId), "차단인데 행이 생겼다");
        }
    }

    /**
     * MinIO 로 넘어간 운영은 <b>허용</b>이다 — 막으면 fallback 이 아무 쓸모가 없다.
     *
     * <p>{@code (OPEN, MINIO_LOCAL)} 이 곧 계약의 {@code LOCAL_ACTIVE} 다(#100). 상태를 담는 칸이
     * 따로 없으므로 "상태만 옮기고 provider 를 안 옮겨 어긋난다" 는 사건 자체가 성립하지 않는다.
     */
    @Nested
    @Import({TestcontainersConfiguration.class, FakeObjectStorageConfiguration.class})
    @SpringBootTest(properties = {
            "app.ai.storage.active-write-provider=MINIO_LOCAL",
            "app.ai.storage.providers.MINIO_LOCAL.endpoint=http://localhost:9",
            "app.ai.storage.providers.MINIO_LOCAL.bucket=test-fallback",
            "app.ai.storage.providers.MINIO_LOCAL.access-key-id=test-access-key",
            "app.ai.storage.providers.MINIO_LOCAL.secret-access-key=test-secret-key"})
    @AutoConfigureMockMvc
    class WhenRunningOnTheFallback extends Fixture {

        @Test
        void grantsAreStillIssued() throws Exception {
            long agentId = agent("폴백");

            mockMvc.perform(uploadUrl(agentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uploadUrl").exists());
        }
    }

    /** 사유마다 공유하는 준비 — 회원·부스·임대·직원. */
    abstract static class Fixture {

        @Autowired protected MockMvc mockMvc;
        @Autowired protected AiDocumentRepository documents;
        @Autowired private BoothRepository booths;
        @Autowired private UserRepository users;
        @Autowired private WalletService wallets;
        @Autowired private MemberSessionService sessions;
        @Autowired private JdbcTemplate jdbc;
        @Autowired private JsonMapper jsonMapper;

        private Long userId;

        @BeforeEach
        void freeSlots() {
            releaseAllSlots(jdbc);
        }

        protected long agent(String prefix) throws Exception {
            userId = createMemberWithWallet(users, wallets, prefix);
            Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
            grantLease(jdbc, boothId, userId);
            String json = mockMvc.perform(post("/api/v1/booths/{id}/agents", boothId)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(AGENT))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return jsonMapper.readTree(json).get("agentId").asLong();
        }

        protected org.springframework.test.web.servlet.RequestBuilder uploadUrl(long agentId) {
            return post("/api/v1/agents/{id}/documents/upload-url", agentId)
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(UPLOAD);
        }

        private String bearer() {
            return "Bearer " + sessions.issue(userId).accessToken();
        }
    }
}
