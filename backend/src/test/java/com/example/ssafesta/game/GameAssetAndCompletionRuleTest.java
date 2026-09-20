package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two rules that were passing projects they should refuse.
 *
 * <p>Both were found by review rather than by a test, which is the point of adding these: an
 * {@code asset://} reference that nothing can resolve and a game with no ending both look fine at
 * save time and fail in front of a player.
 */
class GameAssetAndCompletionRuleTest {

    private static final Long GAME_ID = 123L;

    private final GameProjectValidator validator = new GameProjectValidator();

    // ── ASSET_SOURCE_INVALID ───────────────────────────────────────────────

    /**
     * The server issues {@code asset://game/{gameId}/{assetId}} and nothing else (§2). Any other
     * authority is a reference to something that cannot be resolved, so it is refused whatever it
     * looks like.
     */
    @Test
    void anAssetUriWithAnotherAuthorityIsRefused() {
        assertRule(withAssetSource("asset://uploads/7/whatever.png"), "ASSET_SOURCE_INVALID");
    }

    // ── the server now issues asset://, so state decides (#69, contract §9) ─

    @Test
    void aReadyAssetOfThisGameIsAccepted() {
        ObjectNode project = withAssetSource("asset://game/" + GAME_ID + "/aB7kQ2mZ9xR4tL6vN0wY3sJ8pc");

        assertDoesNotThrow(() -> validator.validateForPublish(project, GameTestSupport.write(project),
                GAME_ID, Map.of("aB7kQ2mZ9xR4tL6vN0wY3sJ8pc", GameAssetState.READY)));
    }

    /**
     * Every not-usable state is refused, and refused at Draft too.
     *
     * <p>Saving a reference to an upload that has not finished is the case that looks harmless: the
     * editor would store it, the upload would fail, and the picture would go missing on another
     * device with nothing having reported an error.
     */
    @Test
    void anAssetThatIsNotReadyIsRefusedAtDraftAndPublish() {
        String assetId = "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc";
        ObjectNode project = withAssetSource("asset://game/" + GAME_ID + "/" + assetId);

        for (GameAssetState state : List.of(GameAssetState.UPLOADING, GameAssetState.FAILED,
                GameAssetState.DELETED, GameAssetState.MISSING)) {
            Map<String, GameAssetState> snapshot = Map.of(assetId, state);
            assertRule(project, snapshot, "ASSET_SOURCE_INVALID");
            GameValidationFailedException draftRefusal = assertThrows(GameValidationFailedException.class,
                    () -> validator.validateForDraft(project, GameTestSupport.write(project), GAME_ID,
                            snapshot));
            assertTrue(draftRefusal.errors().stream().map(ApiErrorDetail::rule)
                            .anyMatch("ASSET_SOURCE_INVALID"::equals),
                    () -> state + " 는 Draft 에서도 거부되어야 한다");
        }
    }

    /**
     * Another game's asset, even one that is {@code READY} there.
     *
     * <p>The authority is compared on the string before any lookup, so this is refused without
     * asking the database whether that asset exists — the answer to that question is not ours to
     * give (contract §2).
     */
    @Test
    void anAssetOfAnotherGameIsRefusedWithoutLookup() {
        String assetId = "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc";
        ObjectNode project = withAssetSource("asset://game/" + (GAME_ID + 1) + "/" + assetId);

        assertRule(project, Map.of(assetId, GameAssetState.READY), "ASSET_SOURCE_INVALID");
    }

    /** A nested path is not the issued form — {@code assetId} is one segment (§2). */
    @Test
    void anAssetUriWithExtraPathSegmentsIsRefused() {
        ObjectNode project = withAssetSource("asset://game/" + GAME_ID + "/nested/sprite.png");

        assertRule(project, Map.of("nested", GameAssetState.READY), "ASSET_SOURCE_INVALID");
    }

    /** The editor's local preview scheme, refused for the same reason it always was. */
    @Test
    void theEditorsLocalPreviewSchemeIsRefused() {
        assertRule(withAssetSource("asset://local/123/sprite"), "ASSET_SOURCE_INVALID");
    }

    @Test
    void inlinePayloadsAreRefused() {
        assertRule(withAssetSource("data:image/png;base64,iVBORw0KGgo="), "ASSET_SOURCE_INVALID");
        assertRule(withAssetSource("blob:https://festa.example/9f1c"), "ASSET_SOURCE_INVALID");
    }

    @Test
    void builtinSourcesStillPass() {
        ObjectNode project = GameTestSupport.validProjectFor(GAME_ID);

        assertDoesNotThrow(() -> validator.validateForPublish(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    // ── COMPLETION_PATH_MISSING ────────────────────────────────────────────

    /**
     * A game nobody can finish must not be published.
     *
     * <p>The contract's own fixture is a 1.0.0 project with no {@code rules}, so the
     * {@code COMPLETE_GAME} action is the whole of its completion path — removing it leaves a world a
     * player can walk around forever.
     */
    @Test
    void aGameWithNoWayToFinishIsRefusedAtPublish() {
        assertRule(withoutCompleteGame(), "COMPLETION_PATH_MISSING");
    }

    /**
     * Draft stores it anyway.
     *
     * <p>An unfinished game is a normal thing to be working on; the rule is about what other people
     * can walk into.
     */
    @Test
    void anUnfinishableGameStillSavesAsADraft() {
        ObjectNode project = withoutCompleteGame();

        assertDoesNotThrow(() -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ObjectNode withAssetSource(String source) {
        ObjectNode project = GameTestSupport.validProjectFor(GAME_ID);
        ((ObjectNode) project.withArray("assets").get(0)).put("source", source);
        return project;
    }

    /** Strips every {@code COMPLETE_GAME} action, leaving the project otherwise valid. */
    private ObjectNode withoutCompleteGame() {
        ObjectNode project = GameTestSupport.validProjectFor(GAME_ID);
        for (JsonNode scene : project.withArray("scenes")) {
            for (JsonNode event : scene.path("events")) {
                stripCompleteGame((ArrayNode) event.path("actions"));
            }
            for (JsonNode node : scene.path("nodes")) {
                for (JsonNode choice : node.path("choices")) {
                    stripCompleteGame((ArrayNode) choice.path("actions"));
                }
            }
        }
        return project;
    }

    private void stripCompleteGame(ArrayNode actions) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            if ("COMPLETE_GAME".equals(actions.get(i).path("type").asText())) {
                actions.remove(i);
            }
        }
    }

    private void assertRule(ObjectNode project, String expectedRule) {
        assertRule(project, Map.of(), expectedRule);
    }

    private void assertRule(ObjectNode project, Map<String, GameAssetState> assetStates, String expectedRule) {
        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID,
                        assetStates));

        List<String> rules = thrown.errors().stream().map(ApiErrorDetail::rule).toList();
        assertTrue(rules.contains(expectedRule),
                () -> expectedRule + " 이어야 하는데 " + rules + " 였다");
    }
}
