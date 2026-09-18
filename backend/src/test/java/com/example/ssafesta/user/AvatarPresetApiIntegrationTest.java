package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import java.util.Map;
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

/** Exercises the member-scoped three-slot avatar preset API from its HTTP contract. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AvatarPresetApiIntegrationTest {

    private static final String FIRST = "sk_01";
    private static final String SECOND = "fa|3=SK_Hair_Long_01|c=FF8800";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;

    @Test
    void anEmptyPresetListOmitsEveryUnusedSlot() throws Exception {
        Long userId = newMember();

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void aMemberCanSaveAndReadAChosenSlot() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, 2, FIRST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value(2))
                .andExpect(jsonPath("$.avatarCode").value(FIRST))
                .andExpect(jsonPath("$.updatedAt").isString());

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slot").value(2))
                .andExpect(jsonPath("$[0].avatarCode").value(FIRST));
    }

    @Test
    void savingTheSameSlotReplacesItsCodeWithoutCreatingAnotherRow() throws Exception {
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, 1, FIRST)).andExpect(status().isOk());

        mockMvc.perform(saveRequest(userId, 1, SECOND))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value(1))
                .andExpect(jsonPath("$.avatarCode").value(SECOND));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM avatar_presets WHERE user_id = ?", Integer.class, userId));
        assertEquals(SECOND, jdbc.queryForObject(
                "SELECT avatar_code FROM avatar_presets WHERE user_id = ? AND slot = 1", String.class, userId));
    }

    @Test
    void listIsAlwaysSortedBySlotRatherThanSaveOrder() throws Exception {
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, 3, FIRST)).andExpect(status().isOk());
        mockMvc.perform(saveRequest(userId, 1, SECOND)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slot").value(1))
                .andExpect(jsonPath("$[1].slot").value(3));
    }

    @Test
    void deleteIsIdempotentAndRemovesOnlyTheRequestedSlot() throws Exception {
        Long userId = newMember();
        mockMvc.perform(saveRequest(userId, 1, FIRST)).andExpect(status().isOk());
        mockMvc.perform(saveRequest(userId, 2, SECOND)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/me/avatar/presets/{slot}", 1).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/users/me/avatar/presets/{slot}", 1).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slot").value(2));
    }

    @Test
    void slotsOutsideOneThroughThreeAreRejectedBeforePersistence() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, 0, FIRST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("slot"));
        mockMvc.perform(delete("/api/v1/users/me/avatar/presets/{slot}", 4).header("Authorization", bearerFor(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("slot"));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM avatar_presets WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void presetCodeUsesTheSameAvatarValidationAsTheCurrentAppearance() throws Exception {
        Long userId = newMember();

        mockMvc.perform(saveRequest(userId, 1, "a".repeat(3801)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("avatarCode"));
    }

    @Test
    void aMemberCannotReadAnotherMembersPresets() throws Exception {
        Long owner = newMember();
        Long viewer = newMember();
        mockMvc.perform(saveRequest(owner, 1, FIRST)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", bearerFor(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void guestsCannotListSaveOrDeletePresets() throws Exception {
        String guest = "Bearer " + accessTokens.issueGuestToken().token();

        mockMvc.perform(get("/api/v1/users/me/avatar/presets").header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
        mockMvc.perform(put("/api/v1/users/me/avatar/presets/{slot}", 1).header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON).content(body(FIRST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
        mockMvc.perform(delete("/api/v1/users/me/avatar/presets/{slot}", 1).header("Authorization", guest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    @Test
    void theMigrationKeepsPresetCodeAsText() {
        assertEquals("text", jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'avatar_presets' AND column_name = 'avatar_code'
                """, String.class));
    }

    private Long newMember() {
        return users.save(new User("프리셋" + SEQUENCE.incrementAndGet())).getId();
    }

    private MockHttpServletRequestBuilder saveRequest(Long userId, int slot, String avatarCode) {
        return put("/api/v1/users/me/avatar/presets/{slot}", slot)
                .header("Authorization", bearerFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content(body(avatarCode));
    }

    private String body(String avatarCode) {
        return json.writeValueAsString(Map.of("avatarCode", avatarCode));
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }
}
