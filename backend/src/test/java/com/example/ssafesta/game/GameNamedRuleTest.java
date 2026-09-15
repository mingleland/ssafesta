package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rules the contract <b>named</b> that the server was answering under a different name, or not at all.
 *
 * <p>These are not new checks. Every project rejected here was already rejected — as
 * {@code MALFORMED_PROJECT}, or as {@code SPRITE_ASSET_INVALID} for all three image slots. What was
 * missing is the name, and the name is the whole point of {@code errors[].rule}: the editor branches
 * on it to put the cursor somewhere. "이 프로젝트는 잘못됐습니다" with no field is a rule that exists
 * only in the changelog.
 */
class GameNamedRuleTest {

    private static final Long GAME_ID = 4242L;

    private final GameProjectValidator validator = new GameProjectValidator();

    // ── 구조 · 상한 (schema 가 잡고, 이름만 붙인다) ────────────────────────

    /**
     * {@code gravity} 1~30 (contract §구조). The schema owns the range; this proves the JSON Pointer
     * it reports actually reaches {@link GameSchemaRuleMap}'s pattern — a mapping keyed on the wrong
     * pointer is silently correct-looking and always falls through to {@code MALFORMED_PROJECT}.
     */
    @Test
    void platformerGravityOutOfRangeIsNamed() {
        ObjectNode project = valid();
        asPlatformer(worldScene(project)).put("gravity", 0);

        assertRule(project, "PLATFORMER_GRAVITY_INVALID");
    }

    /** The contract words the range as "정수 밖", so a fractional gravity is the same rule. */
    @Test
    void fractionalGravityIsTheSameRule() {
        ObjectNode project = valid();
        asPlatformer(worldScene(project)).put("gravity", 1.5);

        assertRule(project, "PLATFORMER_GRAVITY_INVALID");
    }

    // ── 참조 무결성 — Asset 슬롯별 이름 ──────────────────────────────────

    /**
     * A SHOOTER has two image references and the contract gives them different names. Reporting both
     * as {@code SPRITE_ASSET_INVALID} told the editor "an image is wrong" and left it to guess which.
     */
    @Test
    void aShootersMissingProjectileIsItsOwnRule() {
        ObjectNode project = valid();
        ObjectNode shooter = components(firstObject(project)).addObject();
        shooter.put("type", "SHOOTER");
        shooter.put("projectileAssetId", "noSuchArrow");
        shooter.put("damage", 5);
        shooter.put("cooldownMs", 500);

        assertRule(project, "PROJECTILE_ASSET_INVALID");
    }

    @Test
    void aSpawnersMissingEnemyIsItsOwnRule() {
        ObjectNode project = valid();
        ObjectNode spawner = components(firstObject(project)).addObject();
        spawner.put("type", "SPAWNER");
        spawner.put("enemyAssetId", "noSuchSlime");
        spawner.put("intervalMs", 1000);
        spawner.put("maxAlive", 3);

        assertRule(project, "SPAWNER_ASSET_INVALID");
    }

    // ── 중복 id — Dialogue Node 는 Scene 지역 ────────────────────────────

    /**
     * Two nodes with one id inside a single conversation: {@code startNodeId} and every
     * {@code nextNodeId} that names it would have two possible destinations.
     */
    @Test
    void twoNodesWithOneIdInOneDialogueAreRefused() {
        ObjectNode project = valid();
        ArrayNode nodes = (ArrayNode) dialogueScenes(project).get(0).path("nodes");
        nodes.add(nodes.get(0).deepCopy());

        assertRule(project, "DUPLICATE_DIALOGUE_NODE_ID");
    }

    /** It is a <b>common</b> rule (contract §중복 id), so Draft refuses it too — not only Publish. */
    @Test
    void theDuplicateNodeIsRefusedOnDraftAsWell() {
        ObjectNode project = valid();
        ArrayNode nodes = (ArrayNode) dialogueScenes(project).get(0).path("nodes");
        nodes.add(nodes.get(0).deepCopy());

        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForDraft(project, GameTestSupport.write(project), GAME_ID, Map.of()));
        assertTrue(ruleNames(thrown).contains("DUPLICATE_DIALOGUE_NODE_ID"),
                () -> "Draft 에서도 잡혀야 하는데 " + ruleNames(thrown) + " 였다");
    }

    /**
     * The over-blocking guard. Node ids are scene-local — README's namespace list stops at Event, and
     * both references that reach a node resolve inside the owning scene. Two conversations may each
     * open with a node called the same thing, and refusing that would reject valid projects.
     */
    @Test
    void thesameNodeIdInTwoDifferentDialoguesIsAllowed() {
        ObjectNode project = valid();
        List<ObjectNode> dialogues = dialogueScenes(project);
        ObjectNode borrowed = ((ObjectNode) dialogues.get(0).path("nodes").get(0)).deepCopy();
        // Strip the choices: this node exists to carry the id, not to route anywhere.
        ((ArrayNode) borrowed.path("choices")).removeAll();
        ((ArrayNode) dialogues.get(1).path("nodes")).add(borrowed);

        assertDoesNotThrow(
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    // ── DIALOGUE_PRESENTATION_INVALID — FE·계약 검증기와 갈렸던 두 자리 ──

    /**
     * A {@code SHOW_DIALOGUE} pointing at a DIALOGUE scene that is {@code FULL_SCREEN}.
     *
     * <p>The server used to call this {@code DIALOGUE_TARGET_INVALID}, which the reference validator
     * and the editor both reserve for "that scene is not a dialogue at all"
     * ({@code validate-fixtures.mjs:108}, {@code gameProject.ts:693}). Same project, two rule names
     * depending on who validated — and the two names point the cursor at different fields: one at the
     * {@code sceneId}, the other at that scene's {@code presentation}.
     */
    @Test
    void showDialogueAtAFullScreenSceneIsAPresentationFailure() {
        ObjectNode project = valid();
        showDialogueAction(project).put("sceneId", dialogueSceneId(project, "FULL_SCREEN"));

        assertRule(project, "DIALOGUE_PRESENTATION_INVALID");
    }

    /** Pointing at something that is not a DIALOGUE scene stays the target rule — the split holds. */
    @Test
    void showDialogueAtAWorldSceneIsStillATargetFailure() {
        ObjectNode project = valid();
        showDialogueAction(project).put("sceneId", worldScene(project).path("id").asText());

        assertRule(project, "DIALOGUE_TARGET_INVALID");
    }

    /**
     * An unknown {@code presentation} — the schema rejects it first, so it used to leave as
     * {@code MALFORMED_PROJECT} while {@code validate-fixtures.mjs:200} named it. The rule table
     * exists so one project cannot get two names.
     */
    @Test
    void anUnknownPresentationIsNamedRatherThanMalformed() {
        ObjectNode project = valid();
        dialogueScenes(project).get(0).put("presentation", "SIDEBAR");

        assertRule(project, "DIALOGUE_PRESENTATION_INVALID");
    }

    // ── 변수 값 타입 — 서버만 느슨하던 자리 ──────────────────────────────

    /**
     * {@code event-runtime-semantics} compares with strict equality and no conversion, so an INTEGER
     * variable tested against {@code "3"} is a condition that can never be true — a rule the player
     * can never satisfy, saved as if it were fine.
     *
     * <p>The editor's own validator has refused this all along ({@code gameProject.ts}); the server
     * was the loose one, so a project posted straight to the API got through.
     */
    @Test
    void comparingAVariableWithTheWrongTypeIsRefused() {
        ObjectNode project = valid();
        variableEqualsCondition(project, "openDoor").put("value", "false");

        assertRule(project, "VARIABLE_VALUE_TYPE_INVALID");
    }

    /** The assignment half: writing a number into a BOOLEAN corrupts every later comparison. */
    @Test
    void assigningAVariableTheWrongTypeIsRefused() {
        ObjectNode project = valid();
        setVariableAction(project, "openDoor").put("value", 1);

        assertRule(project, "VARIABLE_VALUE_TYPE_INVALID");
    }

    /** A reference rule, so Draft refuses it too (contract §참조 무결성, #48 2026-08-23). */
    @Test
    void theWrongVariableTypeIsRefusedOnDraftAsWell() {
        ObjectNode project = valid();
        setVariableAction(project, "openDoor").put("value", 1);

        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForDraft(project, GameTestSupport.write(project), GAME_ID, Map.of()));
        assertTrue(ruleNames(thrown).contains("VARIABLE_VALUE_TYPE_INVALID"),
                () -> "Draft 에서도 잡혀야 하는데 " + ruleNames(thrown) + " 였다");
    }

    /**
     * One cause, one error. An unknown variable has no declared type to compare against, so adding a
     * type mismatch on top would report the same missing declaration twice and send the editor to a
     * field that is not the problem.
     */
    @Test
    void anUnknownVariableIsNotAlsoReportedAsATypeMismatch() {
        ObjectNode project = valid();
        ObjectNode action = setVariableAction(project, "openDoor");
        action.put("variableId", "noSuchVariable");
        action.put("value", 1);

        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));
        List<String> rules = ruleNames(thrown);
        assertTrue(rules.contains("VARIABLE_REFERENCE_NOT_FOUND"), () -> "참조 없음이어야 하는데 " + rules);
        assertTrue(!rules.contains("VARIABLE_VALUE_TYPE_INVALID"),
                () -> "선언이 없으면 타입 불일치를 겹쳐 보고하지 않는다: " + rules);
    }

    // ── v1.1 rules ──────────────────────────────────────────────────────

    /**
     * {@code uniqueItems} cannot say this: two objectives naming the same goal with different targets
     * differ as objects, and the runtime has no defined answer for which target it counts.
     */
    @Test
    void oneGoalTypeUsedTwiceIsRefused() {
        ObjectNode project = withRules(valid());
        objectives(project).addObject().put("type", "SCORE_AT_LEAST").put("target", 200);

        assertRule(project, "DUPLICATE_OBJECTIVE_TYPE");
    }

    @Test
    void anObjectiveTargetOutOfRangeIsNamed() {
        ObjectNode project = withRules(valid());
        ((ObjectNode) objectives(project).get(0)).put("target", 0);

        assertRule(project, "OBJECTIVE_TARGET_INVALID");
    }

    @Test
    void anUnknownPlayerDefeatIsNamed() {
        ObjectNode project = withRules(valid());
        ((ObjectNode) project.path("rules")).put("playerDefeat", "GIVE_UP");

        assertRule(project, "PLAYER_DEFEAT_INVALID");
    }

    /**
     * The cap the contract deliberately left <b>unnamed</b>: "objectives 5개 초과는 이 rule이 아니라
     * {@code MALFORMED_PROJECT}다". It reaches that by falling through the bound mapping, so a later
     * "let's name every limit" edit has something that fails if it widens this one.
     */
    @Test
    void tooManyObjectivesStayMalformedRatherThanTakingTheTargetRule() {
        ObjectNode project = withRules(valid());
        ArrayNode objectives = objectives(project);
        objectives.removeAll();
        for (String type : new String[] {"SCORE_AT_LEAST", "DEFEAT_ENEMIES", "SURVIVE_SECONDS"}) {
            objectives.addObject().put("type", type).put("target", 10);
        }
        // Six entries with only three legal types — the duplicate rule fires too; the point is that
        // MALFORMED_PROJECT is present and OBJECTIVE_TARGET_INVALID is not.
        for (String type : new String[] {"SCORE_AT_LEAST", "DEFEAT_ENEMIES", "SURVIVE_SECONDS"}) {
            objectives.addObject().put("type", type).put("target", 10);
        }

        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));
        List<String> rules = ruleNames(thrown);
        assertTrue(rules.contains("MALFORMED_PROJECT"), () -> "MALFORMED_PROJECT 여야 하는데 " + rules);
        assertTrue(!rules.contains("OBJECTIVE_TARGET_INVALID"),
                () -> "상한 초과에 target rule 을 붙이면 안 된다: " + rules);
    }

    // ── helpers ─────────────────────────────────────────────────────────

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

    /**
     * The fixture has no PLATFORMER. The two scene types carry identical fields apart from
     * {@code gravity} and wider bounds, so the conversion is a retype plus a legal size.
     */
    private ObjectNode asPlatformer(ObjectNode scene) {
        scene.put("type", "PLATFORMER");
        scene.put("width", 8);
        scene.put("height", 6);
        scene.put("gravity", 10);
        return scene;
    }

    private ObjectNode firstObject(ObjectNode project) {
        return (ObjectNode) worldScene(project).path("objects").get(0);
    }

    private ArrayNode components(ObjectNode object) {
        return (ArrayNode) object.path("components");
    }

    private JsonNode eventById(ObjectNode project, String eventId) {
        for (JsonNode event : worldScene(project).path("events")) {
            if (eventId.equals(event.path("id").asText())) {
                return event;
            }
        }
        throw new IllegalStateException(eventId + " 이벤트가 fixture 에 없다");
    }

    /**
     * By type, never by index: {@code openDoor}'s first condition is {@code HAS_ITEM}, which forbids
     * a {@code value} field outright — writing one there tests {@code additionalProperties}, not the
     * rule under test.
     */
    private ObjectNode variableEqualsCondition(ObjectNode project, String eventId) {
        for (JsonNode condition : eventById(project, eventId).path("conditions")) {
            if ("VARIABLE_EQUALS".equals(condition.path("type").asText())) {
                return (ObjectNode) condition;
            }
        }
        throw new IllegalStateException(eventId + " 에 VARIABLE_EQUALS 가 없다");
    }

    private ObjectNode setVariableAction(ObjectNode project, String eventId) {
        for (JsonNode action : eventById(project, eventId).path("actions")) {
            if ("SET_VARIABLE".equals(action.path("type").asText())) {
                return (ObjectNode) action;
            }
        }
        throw new IllegalStateException(eventId + " 에 SET_VARIABLE 이 없다");
    }

    private ObjectNode showDialogueAction(ObjectNode project) {
        for (JsonNode event : worldScene(project).path("events")) {
            for (JsonNode action : event.path("actions")) {
                if ("SHOW_DIALOGUE".equals(action.path("type").asText())) {
                    return (ObjectNode) action;
                }
            }
        }
        throw new IllegalStateException("SHOW_DIALOGUE 가 fixture 에 없다");
    }

    private String dialogueSceneId(ObjectNode project, String presentation) {
        for (ObjectNode scene : dialogueScenes(project)) {
            if (presentation.equals(scene.path("presentation").asText())) {
                return scene.path("id").asText();
            }
        }
        throw new IllegalStateException(presentation + " DIALOGUE 가 fixture 에 없다");
    }

    private List<ObjectNode> dialogueScenes(ObjectNode project) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode scene : project.withArray("scenes")) {
            if ("DIALOGUE".equals(scene.path("type").asText())) {
                out.add((ObjectNode) scene);
            }
        }
        return out;
    }

    /** The fixture is {@code 1.0.0}, where {@code rules} is forbidden. Move it to {@code 1.1.0}. */
    private ObjectNode withRules(ObjectNode project) {
        project.put("schemaVersion", "1.1.0");
        ObjectNode rules = project.putObject("rules");
        rules.put("playerDefeat", "RESPAWN");
        ObjectNode completion = rules.putObject("completion");
        completion.put("mode", "ALL");
        completion.putArray("objectives").addObject().put("type", "SCORE_AT_LEAST").put("target", 100);
        return project;
    }

    private ArrayNode objectives(ObjectNode project) {
        return (ArrayNode) project.path("rules").path("completion").path("objectives");
    }

    private List<String> ruleNames(GameValidationFailedException thrown) {
        return thrown.errors().stream().map(ApiErrorDetail::rule).toList();
    }

    private void assertRule(ObjectNode project, String expectedRule) {
        GameValidationFailedException thrown = assertThrows(GameValidationFailedException.class,
                () -> validator.validateForPublish(project, GameTestSupport.write(project), GAME_ID, Map.of()));

        List<String> rules = ruleNames(thrown);
        assertTrue(rules.contains(expectedRule),
                () -> expectedRule + " 이어야 하는데 " + rules + " 였다");
    }
}
