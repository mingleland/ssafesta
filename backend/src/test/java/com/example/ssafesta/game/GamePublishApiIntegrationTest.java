package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Publish and the Runtime read (contracts §Publish · §Runtime).
 *
 * <p>The atomicity cases matter most: a version that exists but nobody can see, or a pointer to a
 * version that was never written, are both states no later request can diagnose.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GamePublishApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private GamePublishedVersionRepository published;
    @Autowired private GamePublishService publishService;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;

    @Test
    void publishingAppendsVersionOneAndMovesThePointer() throws Exception {
        Owner owner = savedOwner("발행");

        mockMvc.perform(publish(owner, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.warnings").isArray());

        assertEquals(1, games.findById(owner.gameId()).orElseThrow().getPublishedVersion());
        assertEquals(1, published.highestVersionNo(owner.gameId()));
    }

    /** Publishing again appends; it never rewrites the row already out there. */
    @Test
    void republishingAppendsRatherThanUpdating() throws Exception {
        Owner owner = savedOwner("재발행");
        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());
        var first = published.findByGameIdAndVersionNo(owner.gameId(), 1).orElseThrow();

        saveAgain(owner, 1);
        mockMvc.perform(publish(owner, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2));

        assertEquals(2, games.findById(owner.gameId()).orElseThrow().getPublishedVersion());
        assertEquals(first.getProjectJson(),
                published.findByGameIdAndVersionNo(owner.gameId(), 1).orElseThrow().getProjectJson(),
                "이미 공개된 회차는 바뀌지 않는다");
    }

    /** The draft survives publishing — the creator keeps editing from where they were. */
    @Test
    void theDraftSurvivesPublishing() throws Exception {
        Owner owner = savedOwner("초안보존");

        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());

        mockMvc.perform(get(draftPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
    }

    /**
     * A refused publish leaves nothing behind.
     *
     * <p>This is the whole point of the single transaction. The check is on the database rather than
     * on the response, because a half-applied publish answers with an error either way.
     */
    @Test
    void aRefusedPublishLeavesNoVersionAndNoPointer() throws Exception {
        Owner owner = ownerWithBrokenDraft("롤백");

        assertThrows(GameValidationFailedException.class,
                () -> publishService.publish(owner.gameId(), owner.userId(), 1));

        assertEquals(0, published.highestVersionNo(owner.gameId()));
        assertEquals(null, games.findById(owner.gameId()).orElseThrow().getPublishedVersion());
    }

    @Test
    void publishingAStaleRevisionConflicts() throws Exception {
        Owner owner = savedOwner("발행충돌");

        mockMvc.perform(publish(owner, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].message").value("1"));
    }

    @Test
    void publishingWithNoDraftIsRefused() throws Exception {
        Owner owner = owner("초안없이발행");

        mockMvc.perform(publish(owner, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_VALIDATION_FAILED"));
    }

    // ── Runtime ────────────────────────────────────────────────────────────

    /** Guests may play. Authoring refuses them; this endpoint does not (FR-023). */
    @Test
    void aGuestCanReadAPublishedGame() throws Exception {
        Owner owner = publicPublished("게스트조회");

        mockMvc.perform(get(publishedPath(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.project.gameId").value(owner.gameId()))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")))
                .andExpect(header().string("ETag", "\"v1\""));
    }

    /** Visibility and publishing are independent axes, so this state is legal and has its own code. */
    @Test
    void aPublicGameWithNothingPublishedSaysSo() throws Exception {
        Owner owner = owner("발행전공개");
        makePublic(owner);

        mockMvc.perform(get(publishedPath(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_PUBLISHED"));
    }

    /** Refused for the owner too: the contract has no owner exception and the editor previews locally. */
    @Test
    void aPrivateGameIsRefusedEvenToItsOwner() throws Exception {
        Owner owner = savedOwner("비공개");
        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());

        mockMvc.perform(get(publishedPath(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_NOT_PUBLIC"));
    }

    /**
     * A conditional request with the matching validator answers {@code 304} and no body.
     *
     * <p>Without this the ETag is decorative: the header goes out, the client sends it back, and the
     * server returns the whole snapshot again — up to 2MB on every re-entry.
     */
    @Test
    void anUnchangedVersionAnswers304WithNoBody() throws Exception {
        Owner owner = publicPublished("조건부요청");

        mockMvc.perform(get(publishedPath(owner)).header("If-None-Match", "\"v1\""))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    /** A stale validator gets the full body — the client is behind, not up to date. */
    @Test
    void anOldValidatorStillGetsTheNewVersion() throws Exception {
        Owner owner = publicPublished("낡은검증자");
        saveAgain(owner, 1);
        mockMvc.perform(publish(owner, 2)).andExpect(status().isOk());

        mockMvc.perform(get(publishedPath(owner)).header("If-None-Match", "\"v1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2))
                .andExpect(header().string("ETag", "\"v2\""));
    }

    /**
     * Going private must refuse, not answer "unchanged".
     *
     * <p>This is why the visibility gate runs before the validator comparison: a client holding
     * {@code "v1"} would otherwise keep being told nothing changed while its access was withdrawn.
     */
    @Test
    void aGameTurnedPrivateIsRefusedEvenWithAMatchingValidator() throws Exception {
        Owner owner = publicPublished("비공개전환");
        mockMvc.perform(patch("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(publishedPath(owner)).header("If-None-Match", "\"v1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_NOT_PUBLIC"));
    }

    @Test
    void aMissingGameIsNotFoundOnTheRuntimePath() throws Exception {
        mockMvc.perform(get("/api/v1/games/999999999/published"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Owner publicPublished(String prefix) throws Exception {
        Owner owner = savedOwner(prefix);
        mockMvc.perform(publish(owner, 1)).andExpect(status().isOk());
        makePublic(owner);
        return owner;
    }

    private void makePublic(Owner owner) throws Exception {
        mockMvc.perform(patch("/api/v1/games/" + owner.gameId())
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk());
    }

    private Owner savedOwner(String prefix) throws Exception {
        Owner owner = owner(prefix);
        saveAgain(owner, 0);
        return owner;
    }

    /** A draft the schema accepts but the Dialogue policy does not — Draft stores it, Publish refuses. */
    private Owner ownerWithBrokenDraft(String prefix) throws Exception {
        Owner owner = owner(prefix);
        ObjectNode project = GameTestSupport.loadFixture(
                "/game/fixtures/invalid/invalid-dialogue-target.json");
        project.put("gameId", owner.gameId());
        mockMvc.perform(put(draftPath(owner))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GameTestSupport.saveRequest(0, project)))
                .andExpect(status().isOk());
        return owner;
    }

    private void saveAgain(Owner owner, int expectedRevision) throws Exception {
        mockMvc.perform(put(draftPath(owner))
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

    private String draftPath(Owner owner) {
        return "/api/v1/games/" + owner.gameId() + "/draft";
    }

    private String publishedPath(Owner owner) {
        return "/api/v1/games/" + owner.gameId() + "/published";
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
