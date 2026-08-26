package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Creation, listing, visibility, deletion and restore (contracts §게임 생성 … §복구).
 *
 * <p>The two caps are the reason this file exists. Without the recycle-bin cap a creator can hold 20
 * live games and an unbounded pile of deleted ones, each carrying up to 2MB of draft — and nothing
 * would ever clean it up, because the live cap deliberately ignores them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameLifecycleApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private GameRepository games;
    @Autowired private UserRepository users;
    @Autowired private MemberSessionService sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private GameProperties properties;

    /**
     * Guests may play, not author (헌법 12조 · FR-023 · T087).
     *
     * <p>The refusal is 403 {@code MEMBER_ONLY}, not 401 — the guest <b>is</b> authenticated, and
     * telling them to log in again would send them round a loop that cannot succeed. The other half
     * of this rule, that a guest can read a published game, is fixed in
     * {@code GamePublishApiIntegrationTest.aGuestCanReadAPublishedGame}.
     */
    @Test
    void aGuestCannotCreateAGame() throws Exception {
        mockMvc.perform(post("/api/v1/games")
                        .header("Authorization", "Bearer " + accessTokens.issueGuestToken().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"게스트가 만든 게임\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
    }

    /** Creation makes no draft — the editor holds the starter project and first-saves it. */
    @Test
    void creatingMakesNoDraft() throws Exception {
        Long userId = GameTestSupport.createMember(users, "생성");

        String location = mockMvc.perform(post("/api/v1/games")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"열쇠를 찾아라\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.publishedVersion").doesNotExist())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location + "/draft").header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
    }

    /** The live cap. The message carries the number so no client has to hard-code it. */
    @Test
    void theLiveCapRefusesWithTheLimitInTheMessage() throws Exception {
        Long userId = GameTestSupport.createMember(users, "활성상한");
        for (int i = 0; i < properties.liveLimit(); i++) {
            games.save(new Game(userId, "게임" + i));
        }

        mockMvc.perform(post("/api/v1/games")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"하나 더\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString(String.valueOf(properties.liveLimit()))))
                // The limit is a top-level message, not an errors[] rule — that slot is for the
                // sentence a user reads (#58 T058).
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    /** Soft-deleted games do not count against the live cap: deleting has to free a slot. */
    @Test
    void deletingFreesALiveSlot() throws Exception {
        Long userId = GameTestSupport.createMember(users, "슬롯해제");
        Long doomed = null;
        for (int i = 0; i < properties.liveLimit(); i++) {
            doomed = games.save(new Game(userId, "게임" + i)).getId();
        }

        mockMvc.perform(delete("/api/v1/games/" + doomed).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/games")
                        .header("Authorization", bearerFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"자리 났다\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * A repeat delete answers {@code 404 GAME_DELETED}.
     *
     * <p>The contract tells clients to read it as "already done": a response lost in flight retries
     * into exactly this branch, so treating it as a failure would report a successful delete as one.
     */
    @Test
    void deletingTwiceReportsAlreadyDeleted() throws Exception {
        Owner owner = owner("중복삭제");

        mockMvc.perform(delete(path(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(path(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_DELETED"));
    }

    /**
     * A sixth deletion evicts the oldest and still succeeds.
     *
     * <p>Refusing would tell a user who asked to throw something away to tidy up first. The eviction
     * runs inside the delete transaction, which is what makes the cap a bound and removes any need
     * for a sweeper.
     */
    @Test
    void theRecycleBinEvictsTheOldestRatherThanRefusing() throws Exception {
        Long userId = GameTestSupport.createMember(users, "휴지통");
        Long oldest = null;
        for (int i = 0; i <= properties.deletedLimit(); i++) {
            Long gameId = games.save(new Game(userId, "버릴게임" + i)).getId();
            if (i == 0) {
                oldest = gameId;
            }
            mockMvc.perform(delete("/api/v1/games/" + gameId).header("Authorization", bearerFor(userId)))
                    .andExpect(status().isNoContent());
        }

        assertTrue(games.findById(oldest).isEmpty(), "가장 오래된 삭제본은 영구 삭제된다");
        assertEquals(properties.deletedLimit(), games.countDeletedByOwner(userId));
    }

    /** Restore keeps the visibility it had — deletion was never a visibility change. */
    @Test
    void restoreBringsItBackWithTheSameVisibility() throws Exception {
        Owner owner = owner("복구");
        mockMvc.perform(patch(path(owner))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(path(owner)).header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(path(owner) + "/restore").header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        assertFalse(games.findById(owner.gameId()).orElseThrow().isDeleted());
    }

    /** Restore takes a live slot, so it passes the same gate creation does. */
    @Test
    void restoreIsRefusedWhenTheLiveCapIsFull() throws Exception {
        Long userId = GameTestSupport.createMember(users, "복구상한");
        Long parked = games.save(new Game(userId, "치워둔 게임")).getId();
        mockMvc.perform(delete("/api/v1/games/" + parked).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
        for (int i = 0; i < properties.liveLimit(); i++) {
            games.save(new Game(userId, "활성" + i));
        }

        mockMvc.perform(post("/api/v1/games/" + parked + "/restore")
                        .header("Authorization", bearerFor(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_LIMIT_EXCEEDED"));
    }

    /**
     * Restoring something that is not deleted is idempotent.
     *
     * <p>Deliberately asymmetric with delete's 404: the target state is already reached, so making it
     * an error would need a new code for no gain. "Already deleted" is state the client needs;
     * "already live" is not.
     */
    @Test
    void restoringALiveGameIsIdempotent() throws Exception {
        Owner owner = owner("멱등복구");

        mockMvc.perform(post(path(owner) + "/restore").header("Authorization", bearerFor(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    /** The list carries deleted rows so the editor can offer restore, live ones first. */
    @Test
    void mineListsLiveFirstAndIncludesDeleted() throws Exception {
        Long userId = GameTestSupport.createMember(users, "내목록");
        Long buried = games.save(new Game(userId, "묻은 게임")).getId();
        mockMvc.perform(delete("/api/v1/games/" + buried).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
        games.save(new Game(userId, "살아있는 게임"));

        mockMvc.perform(get("/api/v1/games/mine").header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(2))
                .andExpect(jsonPath("$.games[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.games[1].deletedAt").exists());
    }

    /** Publishing is not required to go public — the two axes are independent. */
    @Test
    void aGameCanBePublicBeforeAnythingIsPublished() throws Exception {
        Owner owner = owner("발행전공개");

        mockMvc.perform(patch(path(owner))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.publishedVersion").doesNotExist());
    }

    @Test
    void anUnknownVisibilityIsRefused() throws Exception {
        Owner owner = owner("잘못된공개값");

        mockMvc.perform(patch(path(owner))
                        .header("Authorization", bearerFor(owner.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("visibility"));
    }

    private Owner owner(String prefix) {
        Long userId = GameTestSupport.createMember(users, prefix);
        Long gameId = games.save(new Game(userId, prefix + " 게임")).getId();
        return new Owner(userId, gameId);
    }

    private String path(Owner owner) {
        return "/api/v1/games/" + owner.gameId();
    }

    private String bearerFor(Long userId) {
        return "Bearer " + sessions.issue(userId).accessToken();
    }

    private record Owner(Long userId, Long gameId) { }
}
