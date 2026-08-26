package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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
     * The server issues no {@code asset://} at all — user upload is outside the MVP (§Asset Boundary)
     * and the endpoint that would mint an id has no contract yet (#69). Accepting one stores a
     * reference to something that cannot exist.
     */
    @Test
    void anArbitraryAssetUriIsRefusedBecauseNothingIssuesOne() {
        assertRule(withAssetSource("asset://uploads/7/whatever.png"), "ASSET_SOURCE_INVALID");
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
                project, GameTestSupport.write(project), GAME_ID));
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
                project, GameTestSupport.write(project), GAME_ID));
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
        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID));

        List<String> rules = thrown.errors().stream().map(ApiErrorDetail::rule).toList();
        assertTrue(rules.contains(expectedRule),
                () -> expectedRule + " 이어야 하는데 " + rules + " 였다");
    }
}
