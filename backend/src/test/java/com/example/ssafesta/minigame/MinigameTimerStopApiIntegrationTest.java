package com.example.ssafesta.minigame;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The wire contract the game part reads ({@code specs/014-minigame/contracts/minigame-api.yaml}),
 * exercised at the <b>shipped configuration</b> — 5~10s targets, +3s failure threshold, 2s elapsed
 * tolerance.
 *
 * <p>That configuration is why the reward paths are not here. Earning a reward means reporting a
 * stop time near the 5~10s target, and the elapsed check requires that time to agree with the
 * server's own clock — which a test would have to actually wait out. The reward, cap and
 * concurrency behaviour is exercised in {@code MinigameRewardIntegrationTest} against a compressed
 * timer instead. What this class proves is everything the shipped numbers <i>can</i> show
 * instantly, including the one case that matters most at production values: reporting the target
 * time the moment the session is issued is refused (T016, FR-008).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MinigameTimerStopApiIntegrationTest {

    private static final String SESSIONS = "/api/v1/minigames/timer-stop/sessions";

    @Autowired private MockMvc mockMvc;
    @Autowired private WalletService wallets;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private UserRepository users;
    @Autowired private MinigameSessionRepository minigameSessions;
    @Autowired private MinigameProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aSessionCarriesAServerChosenTargetInsideTheConfiguredRange() throws Exception {
        String bearer = bearerFor(member("미니시작"));

        // Repeated because the target is drawn per play — one sample says nothing about the range.
        for (int attempt = 0; attempt < 20; attempt++) {
            String issued = issue(bearer);

            UUID.fromString(text(issued, "sessionId"));
            BigDecimal target = number(issued, "targetSeconds");
            assertTrue(target.compareTo(properties.timerStop().targetMinSeconds()) >= 0
                            && target.compareTo(properties.timerStop().targetMaxSeconds()) <= 0,
                    "목표 시간이 설정 범위 밖입니다: " + target);
            assertEquals(3, target.scale(), "목표 시간은 밀리초 단위여야 합니다: " + target);
            assertEquals(0, target.add(properties.timerStop().failMarginSeconds())
                            .compareTo(number(issued, "failAfterSeconds")),
                    "failAfterSeconds 는 목표 + fail-margin 이어야 합니다.");
            assertTrue(issued.contains("\"serverStartedAt\":"), "serverStartedAt 가 없습니다: " + issued);
        }
    }

    @Test
    void reportingTheTargetTimeTheMomentTheSessionIsIssuedIsRefused() throws Exception {
        // The whole of the elapsed check, at production values. Nobody plays a 7-second round in
        // 30 milliseconds, so a claim of "I stopped at 7.381" that arrives now did not happen.
        Long userId = member("미니거부");
        String bearer = bearerFor(userId);
        String issued = issue(bearer);
        BigDecimal target = number(issued, "targetSeconds");
        UUID sessionId = UUID.fromString(text(issued, "sessionId"));
        int balanceBefore = wallets.balanceOf(userId);

        submit(bearer, sessionId, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rewardedCoins").value(0))
                .andExpect(jsonPath("$.tier").value(0));

        assertEquals(MinigameSessionStatus.REJECTED,
                minigameSessions.findByNonce(sessionId).orElseThrow().getStatus());
        assertEquals(balanceBefore, wallets.balanceOf(userId), "거부된 판이 코인을 지급하면 안 됩니다.");
    }

    @Test
    void aStopTimeReportedLongAfterTheRoundIsRefused() throws Exception {
        // The other half of the elapsed check, and the half an upper bound alone would miss
        // (#134 §2 proposed only an upper bound): sit on the session, then report the target as if
        // it had just happened — error 0.000, top band, every time. Without this case a one-sided
        // check passes the whole suite.
        //
        // started_at is moved in SQL rather than slept for: the column is updatable = false and the
        // wait would be a real hour.
        String bearer = bearerFor(member("미니지연"));
        String issued = issue(bearer);
        UUID sessionId = UUID.fromString(text(issued, "sessionId"));
        jdbc.update("update minigame_sessions set started_at = started_at - interval '1 hour' where nonce = ?",
                sessionId);

        submit(bearer, sessionId, number(issued, "targetSeconds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rewardedCoins").value(0))
                .andExpect(jsonPath("$.tier").value(0));

        assertEquals(MinigameSessionStatus.REJECTED,
                minigameSessions.findByNonce(sessionId).orElseThrow().getStatus());
    }

    @Test
    void aRefusedSessionCannotBeRetriedIntoAnAcceptedOne() throws Exception {
        // If a rejected session stayed playable, a client would just resubmit until one claim
        // happened to land inside the tolerance — which is the entire point of the check.
        String bearer = bearerFor(member("미니재시도"));
        String issued = issue(bearer);
        UUID sessionId = UUID.fromString(text(issued, "sessionId"));
        submit(bearer, sessionId, number(issued, "targetSeconds"))
                .andExpect(jsonPath("$.accepted").value(false));

        submit(bearer, sessionId, new BigDecimal("0.010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rewardedCoins").value(0));
    }

    @Test
    void anotherMembersSessionIsIndistinguishableFromOneThatDoesNotExist() throws Exception {
        String owner = bearerFor(member("미니주인"));
        String stranger = bearerFor(member("미니타인"));
        UUID sessionId = UUID.fromString(text(issue(owner), "sessionId"));

        submit(stranger, sessionId, new BigDecimal("1.000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINIGAME_SESSION_NOT_FOUND"));
        submit(stranger, UUID.randomUUID(), new BigDecimal("1.000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINIGAME_SESSION_NOT_FOUND"));
    }

    @Test
    void aMalformedStopTimeIsRefusedAndLeavesTheSessionPlayable() throws Exception {
        String bearer = bearerFor(member("미니오타"));
        UUID sessionId = UUID.fromString(text(issue(bearer), "sessionId"));

        submit(bearer, sessionId, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("stoppedSeconds"));
        submit(bearer, sessionId, new BigDecimal("-1.000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stoppedSeconds"));
        submit(bearer, sessionId, new BigDecimal("100000.000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stoppedSeconds"));

        // A JSON typo must not cost the player their round.
        assertEquals(MinigameSessionStatus.IN_PROGRESS,
                minigameSessions.findByNonce(sessionId).orElseThrow().getStatus());
    }

    @Test
    void aSessionIdThatIsNotAUuidIsARequestError() throws Exception {
        String bearer = bearerFor(member("미니UUID"));

        mockMvc.perform(post(SESSIONS + "/not-a-uuid/result")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stoppedSeconds\":1.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sessionId"));
    }

    @Test
    void aGuestIsRefusedBeforeBeingLetPlay() throws Exception {
        // Refused at issuance, not after the round: letting a guest play and only then saying there
        // was never a reward is the worse of the two.
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(post(SESSIONS).header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
        submit(guest, UUID.randomUUID(), new BigDecimal("1.000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post(SESSIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    void theStartRequestBodyUnityAlreadySendsIsIgnoredRatherThanRefused() throws Exception {
        // HttpGameResultClient posts {gameId}. Refusing it would break a client that is already on
        // develop, and there is nothing to identify anyway — spec 014 FR-009 allows one game.
        mockMvc.perform(post(SESSIONS)
                        .header("Authorization", bearerFor(member("미니바디")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":\"timer-stop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void theExtraResultFieldsUnityAlreadySendsAreIgnoredRatherThanRefused() throws Exception {
        // The shipped client sends {targetSeconds, stoppedSeconds, errorSeconds, timedOut}. Only
        // stoppedSeconds is read; an errorSeconds of 0 must not buy the top band (C-06).
        String bearer = bearerFor(member("미니추가"));
        String issued = issue(bearer);
        UUID sessionId = UUID.fromString(text(issued, "sessionId"));

        mockMvc.perform(post(SESSIONS + "/" + sessionId + "/result")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSeconds\":%s,\"stoppedSeconds\":%s,\"errorSeconds\":0,\"timedOut\":false}"
                                .formatted(number(issued, "targetSeconds"),
                                        number(issued, "targetSeconds"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rewardedCoins").value(0));
    }

    private Long member(String prefix) {
        Long userId = createMember(users, prefix);
        wallets.openWallet(userId);
        return userId;
    }

    private String issue(String bearer) throws Exception {
        return mockMvc.perform(post(SESSIONS).header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Reads a number straight out of the response text rather than through a JSON binder. The
     * assertions here are about exact decimals — 7.381 must be 7.381 — and a binder that hands back
     * a double would decide that for us.
     */
    private static BigDecimal number(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(body);
        assertTrue(matcher.find(), field + " 가 응답에 없습니다: " + body);
        return new BigDecimal(matcher.group(1));
    }

    private static String text(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertTrue(matcher.find(), field + " 가 응답에 없습니다: " + body);
        return matcher.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String bearer, UUID sessionId,
                                                                      BigDecimal stoppedSeconds)
            throws Exception {
        String body = stoppedSeconds == null ? "{}" : "{\"stoppedSeconds\":" + stoppedSeconds + "}";
        return mockMvc.perform(post(SESSIONS + "/" + sessionId + "/result")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
