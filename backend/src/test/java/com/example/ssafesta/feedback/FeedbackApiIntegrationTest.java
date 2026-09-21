package com.example.ssafesta.feedback;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Member submission through to the admin's first-found mark (S15P21A604-953). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FeedbackApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void memberSubmitsAndAdminListsAndMarksFirstFound() throws Exception {
        Long member = member("피드백러");
        Long admin = admin("피드백운영자");

        String feedbackId = mockMvc.perform(post("/api/v1/feedback").header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"미니게임 로딩이 느려요\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").exists())
                .andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(feedbackId.replaceAll(".*\"feedbackId\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/v1/admin/feedback").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("미니게임 로딩이 느려요"))
                .andExpect(jsonPath("$.content[0].firstFound").value(false));

        mockMvc.perform(patch("/api/v1/admin/feedback/" + id + "/first-found")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstFound\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstFound").value(true));
    }

    @Test
    void guestAndBlankContentAreRefused() throws Exception {
        Long member = member("빈본문");

        mockMvc.perform(post("/api/v1/feedback").header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonAdminIsRefusedTheAdminList() throws Exception {
        Long member = member("일반회원");

        mockMvc.perform(get("/api/v1/admin/feedback").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private Long member(String prefix) {
        return createMemberWithWallet(users, wallets, prefix);
    }

    private Long admin(String prefix) {
        Long userId = member(prefix);
        jdbc.update("UPDATE users SET account_type='ADMIN' WHERE id=?", userId);
        return userId;
    }

    private String bearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
