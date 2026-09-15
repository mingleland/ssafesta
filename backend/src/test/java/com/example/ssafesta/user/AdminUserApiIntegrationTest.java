package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearRoster() {
        jdbc.update("UPDATE users SET is_master = FALSE WHERE is_master");
        jdbc.update("UPDATE users SET account_type = 'MEMBER' WHERE account_type = 'ADMIN'");
    }

    @Test
    void memberCannotUseAccountActions() throws Exception {
        Long member = newMember("일반회원");
        Long target = newMember("정지대상");

        mockMvc.perform(post("/api/v1/admin/users/" + target + "/suspend")
                        .header("Authorization", bearerFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정책 위반\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void suspendAndUnsuspendAreIdempotentAndAudited() throws Exception {
        Long actor = newAdmin("운영자");
        Long target = newMember("정지대상");
        int historiesBefore = count("account_status_histories", target);
        int actionsBefore = count("admin_actions", target);

        mockMvc.perform(post("/api/v1/admin/users/" + target + "/suspend")
                        .header("Authorization", bearerFor(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정책 위반\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/admin/users/" + target + "/suspend")
                        .header("Authorization", bearerFor(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"같은 요청 재시도\"}"))
                .andExpect(status().isNoContent());

        assertEquals(AccountStatus.SUSPENDED, users.findById(target).orElseThrow().getStatus());
        assertEquals(historiesBefore + 1, count("account_status_histories", target));
        assertEquals(actionsBefore + 1, count("admin_actions", target));
        assertEquals("SUSPEND", jdbc.queryForObject(
                "SELECT action FROM admin_actions WHERE target_type='USER' AND target_id=? ORDER BY id DESC LIMIT 1",
                String.class, target));

        mockMvc.perform(post("/api/v1/admin/users/" + target + "/unsuspend")
                        .header("Authorization", bearerFor(actor)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/admin/users/" + target + "/unsuspend")
                        .header("Authorization", bearerFor(actor)))
                .andExpect(status().isNoContent());

        assertEquals(AccountStatus.ACTIVE, users.findById(target).orElseThrow().getStatus());
        assertEquals(historiesBefore + 2, count("account_status_histories", target));
        assertEquals(actionsBefore + 2, count("admin_actions", target));
    }

    @Test
    void masterAndLastActiveAdminAreProtected() throws Exception {
        Long actor = newAdmin("운영자");
        Long master = newMaster("마스터");
        mockMvc.perform(post("/api/v1/admin/users/" + master + "/suspend")
                        .header("Authorization", bearerFor(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"차단\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MASTER_PROTECTED"));
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", master);
        jdbc.update("UPDATE users SET account_type='MEMBER' WHERE id=?", actor);

        Long onlyAdmin = newAdmin("유일관리자");
        mockMvc.perform(post("/api/v1/admin/users/" + onlyAdmin + "/suspend")
                        .header("Authorization", bearerFor(onlyAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"차단\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_LAST_ONE"));
        assertTrue(users.findById(onlyAdmin).orElseThrow().isAdmin());
    }

    @Test
    void historyIsNewestFirstAndPaginated() throws Exception {
        Long actor = newAdmin("운영자");
        Long target = newMember("이력대상");
        suspend(actor, target, "첫 사유");
        unsuspend(actor, target);

        mockMvc.perform(get("/api/v1/admin/users/" + target + "/status-history")
                        .param("page", "0").param("size", "1")
                        .header("Authorization", bearerFor(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].currentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    private void suspend(Long actor, Long target, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/" + target + "/suspend")
                .header("Authorization", bearerFor(actor)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void unsuspend(Long actor, Long target) throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/" + target + "/unsuspend")
                .header("Authorization", bearerFor(actor))).andExpect(status().isNoContent());
    }

    private int count(String table, Long target) {
        String sql = table.equals("account_status_histories")
                ? "SELECT count(*) FROM account_status_histories WHERE user_id=?"
                : "SELECT count(*) FROM admin_actions WHERE target_type='USER' AND target_id=?";
        return jdbc.queryForObject(sql, Integer.class, target);
    }

    private Long newMember(String prefix) {
        return users.save(new User(prefix + UUID.randomUUID().toString().substring(0, 6))).getId();
    }

    private Long newAdmin(String prefix) {
        Long id = newMember(prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", id);
        return id;
    }

    private Long newMaster(String prefix) {
        Long id = newAdmin(prefix);
        jdbc.update("UPDATE users SET is_master=TRUE WHERE id=?", id);
        return id;
    }

    private String bearerFor(Long id) {
        return "Bearer " + sessions.issue(id).accessToken();
    }
}
