package com.example.ssafesta.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The account endpoint's two refusals, neither of which had a test (S15P21A604-388).
 *
 * <p>Avatar save and restore live in {@code MyAccountAvatarApiIntegrationTest}; this class is only
 * about the guards on the same controller.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyAccountApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    /**
     * Withdrawal without the confirmation flag is refused, and the account survives it.
     *
     * <p>The second half is the point: a 400 that had already deleted the account would look the
     * same to the test if only the status were asserted.
     */
    @Test
    void aWithdrawalThatWasNotConfirmedIsRefusedAndChangesNothing() throws Exception {
        Long userId = newMember("탈퇴미확인");
        String bearer = bearerFor(userId);

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_NOT_CONFIRMED"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));
    }

    /**
     * A structurally valid member token whose subject has no row is refused as 401, not 500.
     *
     * <p>Sessions live in Redis and users in PostgreSQL, so the two can disagree — a restored Redis
     * snapshot, or a row removed outside the withdrawal path. {@code USER_NOT_FOUND} is
     * {@code UNAUTHORIZED} rather than {@code NOT_FOUND} deliberately: the client's answer is to
     * log in again, not to look for another resource.
     */
    @Test
    void aTokenWhoseMemberHasNoRowIsUnauthorized() throws Exception {
        String bearer = "Bearer " + sessions.issue(999_999_999L).accessToken();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private Long newMember(String prefix) {
        return users.save(new User(prefix + UUID.randomUUID().toString().substring(0, 6))).getId();
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
