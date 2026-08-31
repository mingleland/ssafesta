package com.example.ssafesta.ai;

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
 * <p>두 사유가 <b>다른 코드로</b> 나가는지가 핵심이다. 할당량 초과는 재시도해도 안 되고 장애는
 * 몇 분 뒤 된다 — 한 코드로 뭉치면 FE 가 안내를 가르지 못하고 사용자는 계속 재시도한다.
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
    @Import({TestcontainersConfiguration.class, FakeDocumentStorageConfiguration.class})
    @SpringBootTest(properties = "app.ai.storage.upload-block=QUOTA_EXCEEDED")
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

    /** 장애·감시 불능 — 잠시 뒤 된다. 503 이다. */
    @Nested
    @Import({TestcontainersConfiguration.class, FakeDocumentStorageConfiguration.class})
    @SpringBootTest(properties = "app.ai.storage.upload-block=UNAVAILABLE")
    @AutoConfigureMockMvc
    class WhenStorageIsBlockedTemporarily extends Fixture {

        @Test
        void theGrantIsRefusedAsUnavailableAndNoRowIsCreated() throws Exception {
            long agentId = agent("장애");

            mockMvc.perform(uploadUrl(agentId))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));

            assertEquals(0, documents.countActive(agentId), "차단인데 행이 생겼다");
        }
    }

    /** 두 사유가 공유하는 준비 — 회원·부스·임대·직원. */
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
