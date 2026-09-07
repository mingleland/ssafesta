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
import org.junit.jupiter.api.function.Executable;

/**
 * Reference integrity, on Draft as well as Publish (#48 완료조건 T033·T088).
 *
 * <p>Each case breaks one pointer in the contract's own valid project. That matters: a rule that
 * fires on a project the reference validator accepts is worse than a missing rule, so every mutation
 * here starts from something known-good and changes exactly one thing.
 */
class GameReferenceRuleTest {

    private static final Long GAME_ID = 123L;

    private final GameProjectValidator validator = new GameProjectValidator();

    /** The point of the family: a dangling pointer is caught at the save, not at the next load. */
    @Test
    void draftRefusesADanglingReferenceTooNotJustPublish() {
        ObjectNode project = valid();
        firstAction(project).put("objectId", "존재하지않는오브젝트");

        assertRule("OBJECT_REFERENCE_NOT_FOUND",
                () -> validator.validateForDraft(project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    @Test
    void aGoToSceneToNowhereIsRefused() {
        ObjectNode project = valid();
        firstAction(project).put("sceneId", "없는장면");

        assertPublishRule(project, "SCENE_REFERENCE_NOT_FOUND");
    }

    @Test
    void anInteractTriggerOnAMissingObjectIsRefused() {
        ObjectNode project = valid();
        ((ObjectNode) event(project, 0).path("trigger")).put("targetId", "없는대상");

        assertPublishRule(project, "TRIGGER_TARGET_NOT_FOUND");
    }

    @Test
    void aMissingVariableIsRefused() {
        ObjectNode project = valid();
        firstAction(project).put("variableId", "없는변수");

        assertPublishRule(project, "VARIABLE_REFERENCE_NOT_FOUND");
    }

    @Test
    void aMissingItemIsRefused() {
        ObjectNode project = valid();
        firstAction(project).put("itemId", "없는아이템");

        assertPublishRule(project, "ITEM_REFERENCE_NOT_FOUND");
    }

    @Test
    void anItemPointingAtAMissingAssetIsRefused() {
        ObjectNode project = valid();
        ((ObjectNode) project.withArray("items").get(0)).put("assetId", "없는에셋");

        assertPublishRule(project, "ITEM_ASSET_NOT_FOUND");
    }

    /**
     * Kind is checked, not only existence.
     *
     * <p>A tile layer pointing at an {@code IMAGE} resolves to a file that loads and then tiles
     * wrongly — harder to diagnose than a missing one.
     */
    @Test
    void aTileLayerPointingAtAnImageRatherThanATilesetIsRefused() {
        ObjectNode project = valid();
        String imageId = assetOfKind(project, "IMAGE");
        ((ObjectNode) scene(project, 0).path("tileLayers").get(0)).put("tilesetAssetId", imageId);

        assertPublishRule(project, "TILESET_ASSET_INVALID");
    }

    /** The schema caps tile data at 10,000 but cannot say it must be exactly width × height. */
    @Test
    void aTileLayerWithTheWrongNumberOfCellsIsRefused() {
        ObjectNode project = valid();
        ((ArrayNode) scene(project, 0).path("tileLayers").get(0).path("data")).add(0);

        assertPublishRule(project, "TILE_COUNT_INVALID");
    }

    /** Refused rather than clamped — FR-034's subject and the shape of T-24. */
    @Test
    void anObjectOutsideTheGridIsRefusedNotClamped() {
        ObjectNode project = valid();
        int width = scene(project, 0).path("width").asInt();
        ((ObjectNode) scene(project, 0).path("objects").get(0).path("position")).put("x", width + 5);

        assertPublishRule(project, "OBJECT_POSITION_INVALID");
    }

    @Test
    void aWorldSceneWithNoPlayerSpawnIsRefused() {
        ObjectNode project = valid();
        ArrayNode objects = (ArrayNode) scene(project, 0).path("objects");
        for (int i = objects.size() - 1; i >= 0; i--) {
            if ("PLAYER_SPAWN".equals(objects.get(i).path("preset").asText())) {
                objects.remove(i);
            }
        }

        assertPublishRule(project, "PLAYER_SPAWN_COUNT_INVALID");
    }

    @Test
    void aVariableWhoseInitialValueContradictsItsTypeIsRefused() {
        ObjectNode project = valid();
        ObjectNode variable = (ObjectNode) project.withArray("variables").get(0);
        variable.put("type", "INTEGER");
        variable.put("initialValue", "삼");

        assertPublishRule(project, "VARIABLE_INITIAL_VALUE_INVALID");
    }

    /**
     * An object in another scene is not a missing object.
     *
     * <p>Ids resolve project-wide. A dialogue choice that hides a door standing in the world scene is
     * an ordinary thing to build, and refusing it would need a scene-locality rule stricter than the
     * contract states — one the reference validator does not apply either.
     *
     * <p>Inserted <b>before</b> the existing {@code CLOSE_DIALOGUE}: terminal actions must come last.
     */
    @Test
    void aReferenceToAnObjectInAnotherSceneIsAccepted() {
        ObjectNode project = valid();
        ObjectNode hide = project.objectNode().put("type", "HIDE_OBJECT").put("objectId", "exitDoor");
        ((ArrayNode) dialogueChoiceActions(project)).insert(0, hide);

        assertDoesNotThrow(() -> validator.validateForPublish(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ObjectNode valid() {
        return GameTestSupport.validProjectFor(GAME_ID);
    }

    private JsonNode scene(ObjectNode project, int index) {
        return project.withArray("scenes").get(index);
    }

    private JsonNode event(ObjectNode project, int index) {
        return scene(project, 0).path("events").get(index);
    }

    /** The first action of the first event — one mutation point every action rule can reuse. */
    private ObjectNode firstAction(ObjectNode project) {
        return (ObjectNode) event(project, 0).path("actions").get(0);
    }

    private String assetOfKind(ObjectNode project, String kind) {
        for (JsonNode asset : project.withArray("assets")) {
            if (kind.equals(asset.path("kind").asText())) {
                return asset.path("id").asText();
            }
        }
        throw new IllegalStateException(kind + " Asset 이 fixture 에 없다");
    }

    /** The actions of the first choice of the first DIALOGUE scene — a place outside the world scene. */
    private JsonNode dialogueChoiceActions(ObjectNode project) {
        for (JsonNode scene : project.withArray("scenes")) {
            if ("DIALOGUE".equals(scene.path("type").asText())) {
                return scene.path("nodes").get(0).path("choices").get(0).path("actions");
            }
        }
        throw new IllegalStateException("DIALOGUE Scene 이 fixture 에 없다");
    }

    private void assertPublishRule(ObjectNode project, String expectedRule) {
        assertRule(expectedRule,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    private void assertRule(String expectedRule, Executable call) {
        GameValidationFailedException thrown =
                assertThrows(GameValidationFailedException.class, call);

        List<String> rules = thrown.errors().stream().map(ApiErrorDetail::rule).toList();
        assertTrue(rules.contains(expectedRule),
                () -> expectedRule + " 이어야 하는데 " + rules + " 였다");
    }
}
