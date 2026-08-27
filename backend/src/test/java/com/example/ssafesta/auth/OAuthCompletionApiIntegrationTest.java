package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The signup endpoint, over HTTP.
 *
 * <p>Nothing tested {@code POST /auth/oauth/complete} through the web layer at all, which is how a
 * taken nickname came to answer 500: {@code RegistrationService} throws exceptions that carry no
 * {@link com.example.ssafesta.common.ErrorCode}, and {@code OAuthCompletionController} does not
 * catch them, so they reached {@code handleUnexpected}. Service-level tests could not see it — they
 * assert the exception type, which is thrown correctly; the loss happens on the way out.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuthCompletionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OAuthHandoffService handoffs;
    @Autowired private UserRepository users;
    @Value("${app.auth.frontend-base-url}") private String trustedOrigin;

    /**
     * A nickname someone already has is the ordinary collision the signup screen exists to report.
     *
     * <p>{@code ErrorCode.NICKNAME_DUPLICATED} has carried the exact message for this since spec
     * 005 — nothing was ever wired to it, so the person picking the nickname was told the server
     * had broken instead.
     */
    @Test
    void aTakenNicknameIsRefusedWithItsOwnCode() throws Exception {
        String taken = users.save(new User("점유닉_" + UUID.randomUUID().toString().substring(0, 6))).getNickname();
        String handoff = handoffs.createRegistration(OAuthProvider.GOOGLE, "sub-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/auth/oauth/complete")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("oauth_handoff", handoff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + taken + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    /**
     * A call from anywhere but the app is refused, and <b>not</b> in the error envelope.
     *
     * <p>This endpoint has no Origin check of its own — unlike {@code /auth/refresh} and
     * {@code /auth/logout}, which throw {@code UNTRUSTED_ORIGIN}. Here the CORS layer refuses the
     * request before any controller is chosen, so nothing gives it a {@code code}. The contract
     * document said otherwise until review of !56 caught it; this test is what the document is now
     * written against.
     */
    @Test
    void aCallFromAnotherOriginIsRefusedOutsideTheEnvelope() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/oauth/complete")
                        .header(HttpHeaders.ORIGIN, "http://not-the-app.example")
                        .cookie(new Cookie("oauth_handoff", "irrelevant"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"아무개\"}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"code\""), "CORS 거절은 오류 봉투가 아니다 — 계약에 code 를 약속하면 안 된다: " + body);
    }

    /**
     * A handoff that was never issued must not look like a server fault either.
     *
     * <p>Pinned to 410, not to {@code is4xxClientError()}: the frontend's restart branch is written
     * against 400 and 410 specifically, and a loose status assertion would stay green if this drifted
     * to 400 or 409. That is the same slack that let #113 ship under two green tests (T-126).
     */
    @Test
    void anUnknownHandoffIsRefusedWithItsOwnCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/complete")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .cookie(new Cookie("oauth_handoff", "never-issued"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"아무개\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("OAUTH_HANDOFF_EXPIRED"));
    }
}
