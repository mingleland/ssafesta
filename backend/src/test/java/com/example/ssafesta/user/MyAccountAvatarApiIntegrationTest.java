package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Avatar save and restore (spec 013a FR-013, #24 contract).
 *
 * <p>The load-bearing assertion here is <b>round-trip fidelity</b>: what goes in comes back out
 * unchanged. The server is told not to parse this string (contract §서버 검증), so any trim, case
 * fold or default substitution would be a silent divergence from what Unity encoded — and a
 * silently rewritten appearance value is exactly the shape T-24 took.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyAccountAvatarApiIntegrationTest {

    /** The real encoder's runtime form: pipes, digits, underscores, mixed case, a colour suffix. */
    private static final String MODULAR = "fa|3=SK_Hair_Long_01|c=FF8800";
    private static final String PRESET = "sk_01";
    private static final int MAX_LENGTH = 3800;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;

    // ---------------------------------------------------------------- US1: save

    @Test
    void aPresetCodeIsStoredAndEchoed() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, PRESET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(PRESET));

        assertEquals(PRESET, storedCodeOf(userId));
    }

    @Test
    void aModularCodeSurvivesTheRoundTripUnchanged() throws Exception {
        // Pipes, underscores and upper case all have to pass. Narrowing the charset to something
        // like [a-z0-9_] would reject part names the Sidekick assets actually produce.
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, MODULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(MODULAR));

        assertEquals(MODULAR, storedCodeOf(userId),
                "저장된 값이 요청과 같아야 합니다 — 서버는 이 문자열을 파싱·정규화하지 않습니다");
    }

    @Test
    void surroundingWhitespaceIsPreservedRatherThanTrimmed() throws Exception {
        // Not because padded codes are expected, but because trimming is the server deciding what
        // the client meant. The contract forbids that; if the value is wrong the client owns it.
        Long userId = newMember();
        String padded = " " + PRESET + " ";

        mockMvc.perform(saveRequest(userId, padded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(padded));

        assertEquals(padded, storedCodeOf(userId));
    }

    @Test
    void exactlyTheMaximumLengthIsAccepted() throws Exception {
        // 3800 is Unity's AvatarAppearance.MaxEncodedLength. Lowering it here would reject
        // encodings the client considers valid (헌법 23조 — "29~32자"는 무효).
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, "a".repeat(MAX_LENGTH)))
                .andExpect(status().isOk());

        assertEquals(MAX_LENGTH, storedCodeOf(userId).length());
    }

    @Test
    void aCodeOverTheMaximumLengthIsRefusedWithItsReason() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, "a".repeat(MAX_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("avatarCode"))
                .andExpect(jsonPath("$.errors[0].message").isString())
                .andExpect(jsonPath("$.errors[0].objectId").doesNotExist())
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void aBlankCodeIsRefused() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("avatarCode"));
    }

    @Test
    void aControlCharacterIsRefused() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, "sk_01\nsk_02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("avatarCode"));
    }

    @Test
    void aNonAsciiCharacterIsRefused() throws Exception {
        // The encoder cannot produce one, so accepting it only widens what a forged request may
        // store (헌법 16조).
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, "sk_01_한글"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("avatarCode"));
    }

    @Test
    void eachRejectionSaysWhichRuleItBroke() throws Exception {
        // SC-005: no request ends without a reason the user can act on. Three different faults
        // answering with one generic sentence would leave "왜 안 되지"에 답이 없다 (T-24).
        Long userId = newMember();

        String blank = rejectionMessageOf(userId, "  ");
        String tooLong = rejectionMessageOf(userId, "a".repeat(MAX_LENGTH + 1));
        String charset = rejectionMessageOf(userId, "sk_01_한글");

        assertEquals(3, Set.of(blank, tooLong, charset).size(),
                "빈 값·길이 초과·문자셋 위반이 서로 다른 문장을 받아야 합니다 — [%s] [%s] [%s]"
                        .formatted(blank, tooLong, charset));
    }

    @Test
    void aGuestCannotSaveAnAppearance() throws Exception {
        // 헌법 12조. The contract says Unity skips the call for guests; 헌법 16조 says we do not
        // take the client's word for it.
        mockMvc.perform(put("/api/v1/users/me/avatar")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body(PRESET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void anUnauthenticatedRequestIsRefused() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body(PRESET)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- US3: restore

    @Test
    void aNewMemberHasNoAppearanceYet() throws Exception {
        // null, not a preset. Choosing a default is the client's job (FR-010) — a server-invented
        // one would be indistinguishable from a real choice on the next read. The key is present
        // and empty, not omitted: this field always exists on a profile.
        Long userId = newMember();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").isEmpty())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("avatarCode")));
    }

    @Test
    void aSavedAppearanceComesBackOnTheProfile() throws Exception {
        // The restore path Unity actually uses: UserProfileDto.avatarCode from GET /users/me.
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, MODULAR)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(MODULAR))
                .andExpect(jsonPath("$.nickname").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void savingAgainReplacesTheStoredAppearance() throws Exception {
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, PRESET)).andExpect(status().isOk());
        mockMvc.perform(saveRequest(userId, MODULAR)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearerFor(userId)))
                .andExpect(jsonPath("$.avatarCode").value(MODULAR));
    }

    @Test
    void changingTheNicknameStillReturnsTheAppearance() throws Exception {
        // MyAccountResponse is built in two places. Updating only me() would drop the field from
        // the nickname response alone — the kind of gap no single-endpoint test would catch.
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, PRESET)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(json.writeValueAsString(Map.of("nickname", newNickname()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarCode").value(PRESET));
    }

    // ------------------------------------------------------------------ helpers

    private Long newMember() {
        return users.save(new User(newNickname())).getId();
    }

    private static String newNickname() {
        return "아바타" + SEQUENCE.incrementAndGet() + "x" + System.nanoTime();
    }

    private MockHttpServletRequestBuilder saveRequest(Long userId, String avatarCode) {
        return put("/api/v1/users/me/avatar")
                .header("Authorization", bearerFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content(body(avatarCode));
    }

    private String body(String avatarCode) {
        return json.writeValueAsString(Map.of("avatarCode", avatarCode));
    }

    /** The one sentence the user would see, not the whole envelope. */
    private String rejectionMessageOf(Long userId, String avatarCode) throws Exception {
        String payload = mockMvc.perform(saveRequest(userId, avatarCode))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(payload, "$.errors[0].message");
    }

    private String storedCodeOf(Long userId) {
        return jdbc.queryForObject("SELECT avatar_code FROM users WHERE id = ?", String.class, userId);
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
