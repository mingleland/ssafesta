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
 * Rules whose correctness is about <b>scope</b> rather than existence.
 *
 * <p>Every one of these passed before: an id was unique in its own scene, a trigger pointed at a real
 * object, a {@code CLOSE_DIALOGUE} named a real action, an ending existed somewhere. What was wrong
 * was where they were allowed to be — the kind of defect that reads as valid data and fails in front
 * of a player.
 */
class GameSemanticScopeRuleTest {

    private static final Long GAME_ID = 123L;

    private final GameProjectValidator validator = new GameProjectValidator();

    /**
     * Object ids share one namespace across the project (contracts README; the duplicate-object-id
     * fixture says "프로젝트 전체에서").
     *
     * <p>Counting per scene contradicted the reference rules, which resolve ids project-wide: two
     * objects called the same thing in different scenes would both be legal and
     * {@code HIDE_OBJECT} on that name would have no defined meaning.
     */
    @Test
    void twoObjectsWithOneIdInDifferentScenesAreRefused() {
        ObjectNode project = valid();
        ObjectNode twin = worldObjects(project).get(0).deepCopy();
        // A second world scene is the only place a cross-scene duplicate can live in this fixture.
        ObjectNode secondWorld = worldScene(project).deepCopy();
        secondWorld.put("id", "roomTwo");
        ((ArrayNode) secondWorld.path("objects")).removeAll();
        ((ArrayNode) secondWorld.path("objects")).add(twin);
        ((ArrayNode) secondWorld.path("events")).removeAll();
        project.withArray("scenes").add(secondWorld);

        assertRule(project, "DUPLICATE_OBJECT_ID");
    }

    /**
     * Triggers are scene-local even though actions are not.
     *
     * <p>{@code event-runtime-semantics} fixes {@code ON_INTERACT} targets as "같은
     * TOP_DOWN/PLATFORMER Scene Object". An event runs only while its scene is current, so one aimed
     * elsewhere can never fire — dead configuration that looks like a working link.
     */
    @Test
    void aTriggerPointingAtAnotherScenesObjectIsRefused() {
        ObjectNode project = valid();
        ObjectNode secondWorld = worldScene(project).deepCopy();
        secondWorld.put("id", "roomTwo");
        ((ArrayNode) secondWorld.path("objects")).removeAll();
        ObjectNode faraway = worldObjects(project).get(0).deepCopy();
        faraway.put("id", "farawayThing");
        ((ArrayNode) secondWorld.path("objects")).add(faraway);
        ((ArrayNode) secondWorld.path("events")).removeAll();
        project.withArray("scenes").add(secondWorld);

        // The object now exists project-wide, so this is a scope failure and not a missing id.
        ((ObjectNode) firstEvent(project).path("trigger")).put("targetId", "farawayThing");

        assertRule(project, "TRIGGER_TARGET_NOT_FOUND");
    }

    /** An action may still reach across scenes — that half was correct and must stay. */
    @Test
    void anActionPointingAtAnotherScenesObjectIsStillAccepted() {
        ObjectNode project = valid();
        ObjectNode hide = project.objectNode().put("type", "HIDE_OBJECT").put("objectId", "exitDoor");
        ((ArrayNode) overlayChoiceActions(project)).insert(0, hide);

        assertDoesNotThrow(() -> validator.validateForPublish(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    /**
     * {@code CLOSE_DIALOGUE} belongs only to a choice inside an {@code OVERLAY} dialogue.
     *
     * <p>A world-scene event runs with no overlay open, so there is nothing to close.
     */
    @Test
    void closeDialogueInAWorldSceneEventIsRefused() {
        ObjectNode project = valid();
        ArrayNode actions = (ArrayNode) firstEvent(project).path("actions");
        actions.removeAll();
        actions.add(project.objectNode().put("type", "CLOSE_DIALOGUE"));

        assertRule(project, "DIALOGUE_CLOSE_CONTEXT_INVALID");
    }

    /** An OVERLAY has nothing underneath it if the game starts there. */
    @Test
    void startingInsideAnOverlayDialogueIsRefused() {
        ObjectNode project = valid();
        project.put("startSceneId", overlaySceneId(project));

        assertRule(project, "DIALOGUE_PRESENTATION_INVALID");
    }

    /** {@code GO_TO_SCENE} replaces the current scene; {@code SHOW_DIALOGUE} is what opens an overlay. */
    @Test
    void goingToAnOverlayDialogueIsRefused() {
        ObjectNode project = valid();
        ArrayNode actions = (ArrayNode) firstEvent(project).path("actions");
        actions.removeAll();
        actions.add(project.objectNode()
                .put("type", "GO_TO_SCENE").put("sceneId", overlaySceneId(project)));

        assertRule(project, "DIALOGUE_PRESENTATION_INVALID");
    }

    /**
     * An ending nothing links to is not an ending (FR-067 — "도달 가능한 COMPLETE_GAME Action").
     *
     * <p>The fixture reaches its ending through a {@code GO_TO_SCENE}; removing that link leaves the
     * action in the document and the player with no way to it.
     */
    @Test
    void anEndingInAnUnreachableSceneIsRefused() {
        ObjectNode project = valid();
        stripSceneTransitionsTo(project, endingSceneId(project));

        assertRule(project, "COMPLETION_PATH_MISSING");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ObjectNode valid() {
        return GameTestSupport.validProjectFor(GAME_ID);
    }

    private ObjectNode worldScene(ObjectNode project) {
        for (JsonNode scene : project.withArray("scenes")) {
            if (!"DIALOGUE".equals(scene.path("type").asText())) {
                return (ObjectNode) scene;
            }
        }
        throw new IllegalStateException("world Scene 이 fixture 에 없다");
    }

    private List<ObjectNode> worldObjects(ObjectNode project) {
        List<ObjectNode> out = new java.util.ArrayList<>();
        worldScene(project).path("objects").forEach(o -> out.add((ObjectNode) o));
        return out;
    }

    private JsonNode firstEvent(ObjectNode project) {
        return worldScene(project).path("events").get(0);
    }

    private String dialogueSceneId(ObjectNode project, String presentation) {
        for (JsonNode scene : project.withArray("scenes")) {
            if ("DIALOGUE".equals(scene.path("type").asText())
                    && presentation.equals(scene.path("presentation").asText())) {
                return scene.path("id").asText();
            }
        }
        throw new IllegalStateException(presentation + " DIALOGUE 가 fixture 에 없다");
    }

    private String overlaySceneId(ObjectNode project) {
        return dialogueSceneId(project, "OVERLAY");
    }

    /** The scene holding {@code COMPLETE_GAME} — the fixture's ending. */
    private String endingSceneId(ObjectNode project) {
        for (JsonNode scene : project.withArray("scenes")) {
            for (JsonNode node : scene.path("nodes")) {
                for (JsonNode choice : node.path("choices")) {
                    for (JsonNode action : choice.path("actions")) {
                        if ("COMPLETE_GAME".equals(action.path("type").asText())) {
                            return scene.path("id").asText();
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("COMPLETE_GAME 이 fixture 에 없다");
    }

    private ArrayNode overlayChoiceActions(ObjectNode project) {
        String overlayId = overlaySceneId(project);
        for (JsonNode scene : project.withArray("scenes")) {
            if (overlayId.equals(scene.path("id").asText())) {
                return (ArrayNode) scene.path("nodes").get(0).path("choices").get(0).path("actions");
            }
        }
        throw new IllegalStateException("OVERLAY choice 가 없다");
    }

    /** Removes every transition that leads to {@code target}, orphaning that scene. */
    private void stripSceneTransitionsTo(ObjectNode project, String target) {
        for (JsonNode scene : project.withArray("scenes")) {
            for (JsonNode event : scene.path("events")) {
                stripTargets((ArrayNode) event.path("actions"), target);
            }
            for (JsonNode node : scene.path("nodes")) {
                for (JsonNode choice : node.path("choices")) {
                    stripTargets((ArrayNode) choice.path("actions"), target);
                }
            }
        }
    }

    private void stripTargets(ArrayNode actions, String target) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            if (target.equals(actions.get(i).path("sceneId").asText())) {
                actions.remove(i);
            }
        }
    }

    private void assertRule(ObjectNode project, String expectedRule) {
        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));

        List<String> rules = thrown.errors().stream().map(ApiErrorDetail::rule).toList();
        assertTrue(rules.contains(expectedRule),
                () -> expectedRule + " 이어야 하는데 " + rules + " 였다");
    }
}
