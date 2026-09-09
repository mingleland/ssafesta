package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /games/{id}/versions} (contracts §버전 목록). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameVersionsApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private GamePublishedVersionRepository published;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ownerSeesHistoryNewestFirstWithNoProjectBody() throws Exception {
        Owner owner = savedOwner("이력");
        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());
        saveAgain(owner, 1);
        mockMvc.perform(publish(owner, 2)).andExpect(status().isOk());

        mockMvc.perform(get(versionsPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(owner.gameId()))
                .andExpect(jsonPath("$.publishedVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].versionNo").value(2))
                .andExpect(jsonPath("$.versions[1].versionNo").value(1))
                .andExpect(jsonPath("$.versions[0].project").doesNotExist());
    }

    @Test
    void someoneElsesGameIsForbidden() throws Exception {
        Owner owner = savedOwner("타인");
        Long stranger = GameTestSupport.createMember(users, "타인침입");

        mockMvc.perform(get(versionsPath(owner)).header("Authorization", bearerFor(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));
    }

    @Test
    void aDeletedGameIsNotFound() throws Exception {
        Owner owner = savedOwner("삭제됨");
        mockMvc.perform(delete("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(versionsPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_DELETED"));
    }

    /** DB-level cap, not a Java truncation — 51 rows seeded, only the newest 50 come back. */
    @Test
    void fiftyOneVersionsAreCappedAtFifty() throws Exception {
        Owner owner = savedOwner("상한");
        for (int versionNo = 1; versionNo <= 51; versionNo++) {
            published.save(new GamePublishedVersion(owner.gameId(), versionNo, "1.0.0",
                    "{\"gameId\":" + owner.gameId() + "}", owner.userId()));
        }

        mockMvc.perform(get(versionsPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(50))
                .andExpect(jsonPath("$.versions[0].versionNo").value(51))
                .andExpect(jsonPath("$.versions[49].versionNo").value(2));
    }

    /**
     * The one value the contract itself gives meaning to: {@code versions} non-empty but
     * {@code publishedVersion == null} means "unpublished, history kept" (contracts §버전 목록,
     * {@code Game}'s own class note). No endpoint reaches this state yet in v1 — nothing ever writes
     * {@code publishedVersion} back to {@code null} once set — so it is seeded directly, the same way
     * {@link #fiftyOneVersionsAreCappedAtFifty} seeds past what the API alone can reach.
     */
    @Test
    void anUnpublishedGameKeepsItsHistoryWithANullPointer() throws Exception {
        Owner owner = savedOwner("공개중단");
        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());
        jdbc.update("UPDATE games SET published_version = NULL WHERE id = ?", owner.gameId());

        mockMvc.perform(get(versionsPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].versionNo").value(1));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Owner savedOwner(String prefix) throws Exception {
        Owner owner = owner(prefix);
        saveAgain(owner, 0);
        return owner;
    }

    private void saveAgain(Owner owner, int expectedRevision) throws Exception {
        mockMvc.perform(put("/api/v1/games/" + owner.gameId() + "/draft")
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(expectedRevision,
                                GameTestSupport.validProjectFor(owner.gameId()))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(
            Owner owner, int expectedRevision) {
        return post("/api/v1/games/" + owner.gameId() + "/publish")
                .header("Authorization", bearerFor(owner.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(GameTestSupport.publishRequest(expectedRevision));
    }

    private Owner owner(String prefix) {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 게임")).getId();
        return new Owner(userId, gameId);
    }

    private String versionsPath(Owner owner) {
        return "/api/v1/games/" + owner.gameId() + "/versions";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
