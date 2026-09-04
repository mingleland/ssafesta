package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every editing path refuses an expired booth, asked in <b>one</b> place (spec 004 만료 계약).
 *
 * <p>Each spec already tests its own path, and that is exactly how the check drifted: the rule was
 * copied into eight services and each test only ever proved its own copy. This one names the paths
 * side by side, so a new editing endpoint that forgets {@code BoothAccessGuard.requireActiveLease}
 * fails here rather than shipping — the failure a per-spec suite structurally cannot produce.
 *
 * <p>The editor's <b>read</b> is deliberately absent: an expired owner must still open their own
 * content (FR-011 보존), which is why {@code findDraft} takes {@code requireEditor} alone.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExpiredBoothEditingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anExpiredLeaseStillRefusesEveryEditingPath() throws Exception {
        Long userId = createMemberWithWallet(users, wallets, "만료편집");
        Long boothId = booths.save(new Booth(userId, "만료편집 부스")).getId();
        grantLease(jdbc, boothId, userId);
        String bearer = "Bearer " + sessions.issue(userId).accessToken();

        // Published while the lease is valid — otherwise publish would fail for want of a draft and
        // the expiry refusal would never be the reason under test.
        BoothLayoutTestSupport.publishLayout(mockMvc, boothId, bearer);
        BoothLayoutTestSupport.expireLease(jdbc, boothId);

        refused(put("/api/v1/booths/{id}/facade", boothId), bearer, "{\"themeCode\":\"MONO\"}");
        refused(put("/api/v1/booths/{id}/homepage", boothId), bearer,
                "{\"homepageUrl\":\"https://example.com\"}");
        refused(post("/api/v1/booths/{id}/layouts/publish", boothId), bearer, null);
        refused(post("/api/v1/booths/{id}/projects", boothId), bearer,
                "{\"name\":\"만료 프로젝트\"}");
        refused(post("/api/v1/booths/{id}/agents", boothId), bearer,
                "{\"name\":\"만료 직원\",\"role\":\"GUIDE\",\"systemPrompt\":\"안내합니다.\"}");
    }

    /**
     * 409 {@code BOOTH_LEASE_EXPIRED}, never 400: the request is well formed and the member is
     * entitled to make it — what changed is the booth. A path that answers 400 here has put its
     * validation ahead of the guard and would tell the owner their input is wrong.
     */
    private void refused(MockHttpServletRequestBuilder request, String bearer, String body)
            throws Exception {
        request.header("Authorization", bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOTH_LEASE_EXPIRED"));
    }
}
