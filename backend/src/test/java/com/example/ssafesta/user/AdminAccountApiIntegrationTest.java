package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import java.util.UUID;
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
 * The admin gate and the two invariants that keep it usable (S15P21A604-743).
 *
 * <p>Privilege is read from the database on every request rather than carried in the token, so
 * these tests assert the consequences of that choice as well as the refusals: a demotion has to
 * bite on the very next call, and the roster must never be allowed to reach zero.
 *
 * <p>The master flag has no API by design, so it is set here the only way it is ever set in
 * production — with SQL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminAccountApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private AdminActionRepository adminActions;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Other suites share this database, and "the last administrator" is a global count. Start each
     * case from a roster this class owns.
     */
    @BeforeEach
    void clearRoster() {
        jdbc.update("UPDATE users SET is_master = FALSE WHERE is_master");
        jdbc.update("UPDATE users SET account_type = 'MEMBER' WHERE account_type = 'ADMIN'");
    }

    @Test
    void aPlainMemberIsRefusedAndAnAdministratorIsNot() throws Exception {
        Long member = newMember("일반회원");
        Long admin = newAdmin("관리자");

        mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", bearerFor(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(admin));
    }

    /**
     * Promotion writes exactly one audit row, and the promoted account works immediately.
     *
     * <p>The audit half is the reason this feature carries a table at all: suspension and coin
     * movements keep their own history, so an unrecorded privilege change would be the one thing
     * nobody could reconstruct.
     */
    @Test
    void promotionGrantsAccessAndLeavesOneAuditRow() throws Exception {
        Long actor = newAdmin("승격자");
        Long target = newMember("승격대상");

        mockMvc.perform(post("/api/v1/admin/admins/" + target)
                        .header("Authorization", bearerFor(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"운영 인계\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target))
                .andExpect(jsonPath("$.master").value(false));

        mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", bearerFor(target)))
                .andExpect(status().isOk());

        var recorded = adminActions.findByTargetTypeAndTargetIdOrderByIdDesc("USER", target);
        assertEquals(1, recorded.size());
        assertEquals("ADMIN_GRANT", recorded.get(0).getAction());
        assertEquals(actor, recorded.get(0).getActorUserId());
        assertEquals("운영 인계", recorded.get(0).getDetail());

        mockMvc.perform(post("/api/v1/admin/admins/" + target).header("Authorization", bearerFor(actor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_ALREADY"));
    }

    /** Demotion is a database read away from every guard, so it has to bite on the next call. */
    @Test
    void demotionTakesEffectOnTheNextRequest() throws Exception {
        Long actor = newAdmin("강등자");
        Long target = newAdmin("강등대상");

        mockMvc.perform(delete("/api/v1/admin/admins/" + target)
                        .param("note", "권한 회수")
                        .header("Authorization", bearerFor(actor)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", bearerFor(target)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertFalse(users.findById(target).orElseThrow().isAdmin());
    }

    /**
     * The master account refuses every action aimed at it, and says so with its own code.
     *
     * <p>A plain {@code FORBIDDEN} would read as "your session is wrong" to a caller who is, in
     * fact, a perfectly good administrator.
     */
    @Test
    void theMasterAccountCannotBeDemoted() throws Exception {
        Long actor = newAdmin("강등시도자");
        Long master = newMaster("마스터");

        mockMvc.perform(delete("/api/v1/admin/admins/" + master).header("Authorization", bearerFor(actor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));

        assertTrue(users.findById(master).orElseThrow().isAdmin());
    }

    /**
     * The roster may not reach zero. The promotion endpoint is itself behind the admin gate, so an
     * empty roster could only be refilled by a migration.
     */
    @Test
    void theLastAdministratorCannotBeDemoted() throws Exception {
        Long onlyAdmin = newAdmin("유일한관리자");

        mockMvc.perform(delete("/api/v1/admin/admins/" + onlyAdmin).header("Authorization", bearerFor(onlyAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_LAST_ONE"));

        assertTrue(users.findById(onlyAdmin).orElseThrow().isAdmin());
    }

    /** Demoting someone who never had the rights leaves the world as the caller asked for it. */
    @Test
    void demotingAPlainMemberSucceedsAndRecordsNothing() throws Exception {
        Long actor = newAdmin("강등자");
        Long target = newMember("관리자아님");

        mockMvc.perform(delete("/api/v1/admin/admins/" + target).header("Authorization", bearerFor(actor)))
                .andExpect(status().isNoContent());

        assertTrue(adminActions.findByTargetTypeAndTargetIdOrderByIdDesc("USER", target).isEmpty());
    }

    /**
     * Promote, demote, withdraw — the sequence that a foreign key on the audit table would break.
     *
     * <p>{@code AccountDeletionService} ends with {@code DELETE FROM users}. If {@code admin_actions
     * .target_user_id} referenced that row, anyone who had ever been promoted could never leave,
     * and the failure would surface as a 500 on withdrawal rather than anywhere near this feature.
     */
    @Test
    void anAccountThatWasOnceAnAdministratorCanStillWithdraw() throws Exception {
        Long actor = newAdmin("승격자");
        Long target = newMember("떠나는사람");

        mockMvc.perform(post("/api/v1/admin/admins/" + target).header("Authorization", bearerFor(actor)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/admins/" + target).header("Authorization", bearerFor(actor)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearerFor(target))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isNoContent());

        assertTrue(users.findById(target).isEmpty());
        assertFalse(adminActions.findByTargetTypeAndTargetIdOrderByIdDesc("USER", target).isEmpty());
    }

    private Long newMember(String prefix) {
        return users.save(new User(prefix + UUID.randomUUID().toString().substring(0, 6))).getId();
    }

    private Long newAdmin(String prefix) {
        Long userId = newMember(prefix);
        jdbc.update("UPDATE users SET account_type = 'ADMIN' WHERE id = ?", userId);
        return userId;
    }

    private Long newMaster(String prefix) {
        Long userId = newAdmin(prefix);
        jdbc.update("UPDATE users SET is_master = TRUE WHERE id = ?", userId);
        return userId;
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
