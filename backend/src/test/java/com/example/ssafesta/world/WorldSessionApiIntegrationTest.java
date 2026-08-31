package com.example.ssafesta.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * World session issuance (spec 002 US2 · FR-005 · FR-012, contract {@code world-session.openapi.yaml}).
 *
 * <p>Two things this file is really guarding. First, <b>the shape</b>: Unity's {@code WorldSessionDto}
 * reads {@code endpoint.scheme} to decide whether to enable encryption, so a flattened URL string
 * would leave the deployed client connecting in the clear. Second, <b>whose identity is in the
 * grant</b>: the nickname and appearance come from the account row, never from the caller (헌법 16조).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WorldSessionApiIntegrationTest {

    private static final String PATH = "/api/v1/world-sessions";
    private static final String AVATAR = "fa|3=SK_Hair_Long_01|c=FF8800";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JsonMapper json;

    // ------------------------------------------------------------- 회원

    @Test
    void aMemberReceivesTheContractShape() throws Exception {
        Long userId = newMember();

        mockMvc.perform(post(PATH).header("Authorization", memberBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(org.hamcrest.Matchers.startsWith("ws_")))
                .andExpect(jsonPath("$.worldId").value("11F"))
                .andExpect(jsonPath("$.channelId").value("11F-01"))
                .andExpect(jsonPath("$.endpoint.scheme").value("ws"))
                .andExpect(jsonPath("$.endpoint.host").value("127.0.0.1"))
                .andExpect(jsonPath("$.endpoint.port").value(7777))
                .andExpect(jsonPath("$.connectionToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void theGrantCarriesTheStoredNicknameAndAppearance() throws Exception {
        // Not what the caller claims — there is no request field for either, by design.
        User user = users.save(new User(newNickname()));
        user.changeAvatarCode(AVATAR);
        users.save(user);

        Map<String, Object> claims = claimsOf(issueFor(memberBearer(user.getId())));

        assertEquals("MEMBER", claims.get("role"));
        assertEquals(String.valueOf(user.getId()), claims.get("playerId"));
        assertEquals(user.getNickname(), claims.get("nickname"));
        assertEquals(AVATAR, claims.get("avatarCode"));
        assertEquals("11F-01", claims.get("channelId"));
    }

    @Test
    void theStatedExpiryMatchesTheGrantAndIsTwoMinutesOut() throws Exception {
        String payload = issueFor(memberBearer(newMember()));
        Map<String, Object> claims = claimsOf(payload);

        long exp = ((Number) claims.get("exp")).longValue();
        long iat = ((Number) claims.get("iat")).longValue();
        assertEquals(120, exp - iat);
        assertEquals(exp, Instant.parse(read(payload, "$.expiresAt")).getEpochSecond());
    }

    @Test
    void aSuspendedMemberIsRefused() throws Exception {
        // The suspension has to reach the world: an already-issued Access Token stays valid until it
        // expires, so without this check a suspended account would still walk in.
        User user = users.save(new User(newNickname()));
        String bearer = memberBearer(user.getId());
        user.suspend("TEST");
        users.save(user);

        mockMvc.perform(post(PATH).header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- 게스트

    @Test
    void aGuestIsAdmittedWithADerivedNameAndNoAppearance() throws Exception {
        // Guests exist to look around (헌법 12조), and infra-003 D-04 makes them part of P0.
        Map<String, Object> claims = claimsOf(issueFor(guestBearer()));

        assertEquals("GUEST", claims.get("role"));
        assertEquals(true, claims.get("nickname").toString().startsWith("게스트-"));
        assertEquals(null, claims.get("avatarCode"));
    }

    // ------------------------------------------------------------- 층 파라미터 (FR-012)

    @Test
    void theOnlyLegalFloorIsAccepted() throws Exception {
        mockMvc.perform(request(memberBearer(newMember()), Map.of("worldId", "11F")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("11F"));
    }

    @Test
    void anUnknownFloorIsRefusedRatherThanRedirected() throws Exception {
        // Ignoring it would send the user somewhere they did not ask for — the silent-fallback shape
        // 헌법 9조 and FR-012 keep the parameter around to avoid.
        mockMvc.perform(request(memberBearer(newMember()), Map.of("worldId", "1F")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("worldId"));
    }

    @Test
    void anIgnoredPartyPreferenceIsStillAccepted() throws Exception {
        // MVP has one channel, so the field influences nothing — but rejecting it would break a
        // client that sends the documented body (docs/08 §16).
        mockMvc.perform(request(memberBearer(newMember()), Map.of("preferredPartyId", "party-1")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- 인증

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------------ helpers

    private Long newMember() {
        return users.save(new User(newNickname())).getId();
    }

    private static String newNickname() {
        // nickname VARCHAR(30) 예산. 태그는 헬퍼 구분용이다 — T-103, BoothTestSupport 참고.
        return "월드v" + SEQUENCE.incrementAndGet();
    }

    private String memberBearer(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private String guestBearer() {
        return "Bearer " + accessTokens.issueGuestToken().token();
    }

    private MockHttpServletRequestBuilder request(String bearer, Map<String, Object> body) {
        return post(PATH).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content(json.writeValueAsString(body));
    }

    private String issueFor(String bearer) throws Exception {
        return mockMvc.perform(post(PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reads the grant's payload without verifying it — the signature is {@link
     * WorldEntryTokenIssuerTest}'s subject, and this test is about which identity ends up inside.
     */
    private Map<String, Object> claimsOf(String responsePayload) {
        String token = read(responsePayload, "$.connectionToken");
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]);
        return json.readValue(decoded, Map.class);
    }

    private static String read(String payload, String path) {
        return com.jayway.jsonpath.JsonPath.read(payload, path);
    }
}
