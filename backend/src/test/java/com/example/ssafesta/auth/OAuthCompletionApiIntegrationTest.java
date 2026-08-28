package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.OAuthProvider;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The signup endpoint, over HTTP.
 *
 * <p>Nothing tested {@code POST /auth/oauth/complete} through the web layer at all, which is how a
 * taken nickname came to answer 500: {@code RegistrationService} throws exceptions that carry no
 * {@link com.example.ssafesta.common.ErrorCode}, and {@code OAuthCompletionController} does not
 * catch them, so they reached {@code handleUnexpected}. The one service-level test there was covered
 * the opposite case — an existing member being returned — so no test ever ran a signup failure at
 * all, at either layer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuthCompletionApiIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @MockitoSpyBean private OAuthHandoffService handoffs;
    @MockitoSpyBean private MemberSessionService sessions;
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

    /**
     * A refused nickname must leave the handoff alive, or the 409 above is a dead end.
     *
     * <p>The frontend keeps its form open and asks for another nickname, which is the right thing to
     * do — but the handoff was spent on read, before the nickname was ever checked, so that second
     * submission came back {@code OAUTH_HANDOFF_EXPIRED} and the person had to redo the whole OAuth
     * round trip. Measured before the fix: {@code 409} then {@code 410}. FR-021c spends the handoff
     * only on a valid submission.
     */
    @Test
    void aRefusedNicknameLeavesTheHandoffUsable() throws Exception {
        String taken = users.save(new User("점유_" + UUID.randomUUID().toString().substring(0, 6))).getNickname();
        String handoff = handoffs.createRegistration(OAuthProvider.GOOGLE, "sub-" + UUID.randomUUID());

        complete(handoff, taken).andExpect(status().isConflict());

        complete(handoff, "닉" + UUID.randomUUID().toString().substring(0, 8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    /**
     * The single bit the controller leans on: a handoff can be spent exactly once.
     *
     * <p>The concurrent test above cannot reach this reliably — registration usually collides on the
     * identity constraint first and never gets as far as the handoff. So the guarantee is pinned
     * here directly, where it is deterministic. {@code discard} returning void was how the read-then
     * -delete split lost it.
     */
    @Test
    void aHandoffCanOnlyBeSpentOnce() {
        String handoff = handoffs.createRegistration(OAuthProvider.GOOGLE, "once-" + UUID.randomUUID());

        assertTrue(handoffs.discard(handoff), "처음 소비는 성공해야 합니다.");
        assertFalse(handoffs.discard(handoff),
                "두 번째 소비는 실패해야 합니다 — 이 한 비트가 세션 이중 발급을 막습니다.");
    }

    /**
     * Losing the handoff mid-flight must cost the session, not just the handoff.
     *
     * <p>{@link #aHandoffCanOnlyBeSpentOnce} pins the primitive; this pins that the controller acts
     * on it. Between them sits the only line that matters — the caller registers successfully and
     * then finds the handoff already spent, and must <b>not</b> issue anyway, because issuing revokes
     * whatever session the winner just received.
     *
     * <p>Forcing {@code discard} to report a loss is the only way in: the window is real but too
     * narrow to hit on purpose, and a concurrent test cannot reach it — registration collides on the
     * identity constraint first. I had called the gate untestable for that reason. It is not; the
     * seam was a spy away (raised in review of !56).
     */
    @Test
    void aHandoffLostBetweenReadAndSpendIssuesNoSession() throws Exception {
        String subject = "lost-" + UUID.randomUUID();
        String handoff = handoffs.createRegistration(OAuthProvider.GOOGLE, subject);
        doReturn(false).when(handoffs).discard(handoff);

        complete(handoff, "놓친" + UUID.randomUUID().toString().substring(0, 6))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("OAUTH_HANDOFF_EXPIRED"));

        verify(sessions, never()).issue(anyLong());
    }

    /** The contract promises 400 here and the frontend restart branch is written against it. */
    @Test
    void aMissingHandoffCookieIsRefusedWithItsOwnCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/complete")
                        .header(HttpHeaders.ORIGIN, trustedOrigin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"아무개\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_HANDOFF_MISSING"));
    }

    /**
     * A nickname the policy refuses is the other half of "a refused nickname keeps the handoff".
     *
     * <p>{@link #aRefusedNicknameLeavesTheHandoffUsable} covers the duplicate; this covers the policy
     * rejection, which the contract lists beside it. Both are refused before the handoff is touched,
     * and the contract tells the frontend it may keep its form open — so both need pinning, not just
     * the one that happened to be convenient.
     */
    @Test
    void aRefusedNicknameFormatLeavesTheHandoffUsable() throws Exception {
        String handoff = handoffs.createRegistration(OAuthProvider.GOOGLE, "policy-" + UUID.randomUUID());

        complete(handoff, "admin")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NICKNAME_INVALID"));

        complete(handoff, "정상닉" + UUID.randomUUID().toString().substring(0, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    private ResultActions complete(String handoff, String nickname) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth/complete")
                .header(HttpHeaders.ORIGIN, trustedOrigin)
                .cookie(new Cookie("oauth_handoff", handoff))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}"));
    }
}
