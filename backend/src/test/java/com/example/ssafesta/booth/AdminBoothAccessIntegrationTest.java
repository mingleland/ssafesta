package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * The global-admin branch of the shared booth access gate (S15P21A604-742).
 *
 * <p>Existing per-feature tests prove that their own owner path works. This class instead proves
 * the cross-cutting property that makes the admin model useful: an administrator can operate a
 * different member's booth, that operation is audited, and the master account remains protected.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminBoothAccessIntegrationTest {

    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void administratorCanChangeAnotherMembersHomepageAndLayoutAndEachChangeIsAudited() throws Exception {
        Owner owner = leasedOwner("일반소유자");
        Long administrator = administrator("운영자");
        String bearer = bearer(administrator);

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://admin.example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/booths/{id}/layouts/draft", owner.boothId())
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(0)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/booths/{id}/dashboard/summary", owner.boothId())
                        .header("Authorization", bearer)
                        .queryParam("from", "2026-09-01T00:00:00Z")
                        .queryParam("to", "2026-09-16T00:00:00Z"))
                .andExpect(status().isOk());

        assertAuditCount(administrator, owner.boothId(), 2);
    }

    @Test
    void administratorCannotChangeMasterOwnedBoothButMasterCanStillChangeTheirOwn() throws Exception {
        Owner master = leasedOwner("마스터소유자");
        jdbc.update("UPDATE users SET account_type='ADMIN', is_master=TRUE WHERE id=?", master.userId());
        Long administrator = administrator("다른운영자");

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", master.boothId())
                        .header("Authorization", bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://blocked.example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));

        mockMvc.perform(post("/api/v1/booths/{id}/layouts/publish", master.boothId())
                        .header("Authorization", bearer(administrator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));

        mockMvc.perform(get("/api/v1/booths/{id}/dashboard/summary", master.boothId())
                        .header("Authorization", bearer(administrator))
                        .queryParam("from", "2026-09-01T00:00:00Z")
                        .queryParam("to", "2026-09-16T00:00:00Z"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", master.boothId())
                        .header("Authorization", bearer(master.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://master.example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void demotedAdministratorLosesCrossBoothAccessOnTheirNextRequest() throws Exception {
        Owner owner = leasedOwner("강등대상");
        Long administrator = administrator("강등운영자");
        jdbc.update("UPDATE users SET account_type='MEMBER' WHERE id=?", administrator);

        mockMvc.perform(put("/api/v1/booths/{id}/homepage", owner.boothId())
                        .header("Authorization", bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homepageUrl\":\"https://demoted.example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOTH_EDITOR_FORBIDDEN"));
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private Long administrator(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private void assertAuditCount(Long actorUserId, Long boothId, int expected) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM admin_actions
                 WHERE actor_user_id=? AND action='BOOTH_EDIT' AND target_type='BOOTH' AND target_id=?
                """, Integer.class, actorUserId, boothId);
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }

    private record Owner(Long userId, Long boothId) { }
}
