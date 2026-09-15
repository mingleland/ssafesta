package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reproduces {@code contracts/fixtures/manifest.json} against the server's validator.
 *
 * <p>quickstart asks for exactly this: the same positive project accepted and the same negative ones
 * refused <b>with the same code</b>. It is the only test that can catch the server and the reference
 * validator drifting apart, which is the failure the manifest exists to prevent — a project that
 * passes the fixtures and is rejected in production reads as a contract violation rather than as a
 * bug.
 *
 * <p>No Spring context: the validator has no collaborators, and a plain unit test says so.
 */
class GameFixtureContractTest {

    private static final Long GAME_ID = 123L;

    private final GameProjectValidator validator = new GameProjectValidator();

    @Test
    void theValidProjectPassesBothEntryPoints() {
        ObjectNode project = GameTestSupport.loadFixture("/game/fixtures/minimal-top-down-dialogue.json");
        String body = GameTestSupport.write(project);

        assertDoesNotThrow(() -> validator.validateForDraft(project, body, GAME_ID, Map.of()));
        assertDoesNotThrow(() -> validator.validateForPublish(project, body, GAME_ID, Map.of()));
    }

    /**
     * An unsupported MAJOR is a top-level {@code code}, not a rule.
     *
     * <p>The client's fix is to upgrade, not to edit the document, so it cannot arrive inside a list
     * of things to correct.
     */
    @Test
    void anUnsupportedSchemaVersionIsItsOwnCode() {
        ApiException thrown = assertThrows(ApiException.class, () -> validate("unsupported-schema"));

        assertEquals(ErrorCode.GAME_SCHEMA_UNSUPPORTED, thrown.errorCode());
    }

    @Test
    void aMissingStartSceneIsRefused() {
        assertRule("missing-start-scene", "START_SCENE_NOT_FOUND");
    }

    @Test
    void aDuplicateObjectIdIsRefused() {
        assertRule("duplicate-object-id", "DUPLICATE_OBJECT_ID");
    }

    @Test
    void showDialogueMustTargetAnOverlayDialogueScene() {
        assertRule("invalid-dialogue-target", "DIALOGUE_TARGET_INVALID");
    }

    @Test
    void aChoiceCannotHaveBothNextNodeAndTerminalAction() {
        assertRule("dialogue-next-with-terminal", "DIALOGUE_NEXT_WITH_TERMINAL_ACTION");
    }

    @Test
    void closeDialogueIsMeaninglessInFullScreen() {
        assertRule("invalid-dialogue-close-context", "DIALOGUE_CLOSE_CONTEXT_INVALID");
    }

    /**
     * Publish is the entry point under test for every negative fixture: three of the six are Dialogue
     * policy, which Draft deliberately does not check (a half-written conversation is a normal thing
     * to save). Running the whole manifest through the stricter door keeps one assertion shape.
     */
    private void assertRule(String fixture, String expectedRule) {
        GameValidationFailedException thrown =
                assertThrows(GameValidationFailedException.class, () -> validate(fixture));

        List<String> rules = thrown.errors().stream().map(ApiErrorDetail::rule).toList();
        assertTrue(rules.contains(expectedRule),
                () -> fixture + " 는 " + expectedRule + " 이어야 하는데 " + rules + " 였다");
    }

    private void validate(String fixture) {
        ObjectNode project = GameTestSupport.loadFixture("/game/fixtures/invalid/" + fixture + ".json");
        validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of());
    }
}
