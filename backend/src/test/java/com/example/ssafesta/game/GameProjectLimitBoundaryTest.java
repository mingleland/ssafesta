package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * S15P21A604-744 — FE({@code gameProject.ts} {@code GAME_PROJECT_LIMITS}·{@code recordAt})와 BE(이
 * 파일이 직접 부르는 {@link GameProjectValidator})의 상한·필드 정합성을 실측한다.
 *
 * <p>각 배열 상한은 <b>한 번의 append로 통째로</b> 채운다 — 원소마다 문서 전체를 다시 검증하는
 * 구조가 아니라서(스키마 검증은 한 번의 {@code validateForDraft} 호출 안에서 끝난다) 반복 추가
 * 자체는 O(n²) 위험이 없지만, 그래도 굳이 한 번씩 append할 이유가 없어 배열을 만들어 한 번에
 * 붙인다.
 */
class GameProjectLimitBoundaryTest {

    private static final Long GAME_ID = 744L;

    private final GameProjectValidator validator = new GameProjectValidator();

    // ── 이번 티켓에서 고친 필드 — 회귀 방지 ──────────────────────────────

    /** S15P21A604-534 "상호작용 거리" — BE 스키마에 없어서 저장이 막혀 있었다. */
    @Test
    void interactableRangeIsNowAccepted() {
        ObjectNode project = valid();
        ObjectNode interactable = components(firstObject(project)).addObject();
        interactable.put("type", "INTERACTABLE");
        interactable.put("prompt", "조사하기");
        interactable.put("range", 5);

        assertDoesNotThrow(() -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    @Test
    void interactableRangeOutOfBoundsIsStillRefused() {
        ObjectNode project = valid();
        ObjectNode interactable = components(firstObject(project)).addObject();
        interactable.put("type", "INTERACTABLE");
        interactable.put("prompt", "조사하기");
        interactable.put("range", 101);

        assertThrows(GameValidationFailedException.class, () -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    /** 오브젝트 이름 표시(S15P21A604-529 QA 확정) — BE 스키마에 둘 다 없어서 저장이 막혀 있었다. */
    @Test
    void objectNameAndShowNameInPlayAreNowAccepted() {
        ObjectNode project = valid();
        // firstObject는 PLAYER_SPAWN이라 preset을 바꾸면 "Scene당 PLAYER_SPAWN 정확히 1개" 규칙을
        // 건드린다 — 이 테스트가 보려는 것과 무관하므로 두 번째 오브젝트(ITEM)를 쓴다.
        ObjectNode object = (ObjectNode) worldScene(project).path("objects").get(1);
        object.put("name", "황동 열쇠 더미");
        object.put("showNameInPlay", true);

        assertDoesNotThrow(() -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    /** BE가 자기 설계(specs 원본)보다 느슨하게 검증하고 있던 자리 — 타입별 상한이 실제로 걸리는지. */
    @Test
    void defeatEnemiesObjectiveOverItsOwnCapIsRefusedEvenThoughUnderTheGlobalCap() {
        ObjectNode project = withRules(valid());
        ((ObjectNode) objectives(project).get(0)).put("type", "DEFEAT_ENEMIES").put("target", 10_001);

        assertThrows(GameValidationFailedException.class, () -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    @Test
    void defeatEnemiesObjectiveAtItsOwnCapIsAccepted() {
        ObjectNode project = withRules(valid());
        ((ObjectNode) objectives(project).get(0)).put("type", "DEFEAT_ENEMIES").put("target", 10_000);

        assertDoesNotThrow(() -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    // ── 숫자 상한 9종 — 정확히 상한/상한+1 ────────────────────────────────

    @Test
    void sceneCountBoundary() {
        assertBoundary(50, this::projectWithScenes);
    }

    @Test
    void objectsPerSceneBoundary() {
        assertBoundary(500, this::projectWithExtraSceneObjects);
    }

    @Test
    void eventsPerSceneBoundary() {
        assertBoundary(300, this::projectWithExtraSceneEvents);
    }

    @Test
    void tileLayersPerSceneBoundary() {
        assertBoundary(10, this::projectWithExtraSceneTileLayers);
    }

    /**
     * 다른 8종과 달리 "상한+1"을 만들 수 없다 — {@code data.length}는 항상 {@code width*height}와
     * 정확히 같아야 하는데(TILE_COUNT_INVALID), TOP_DOWN width·height는 각각 최대 100이라
     * 100*100=10,000이 애초에 도달 가능한 최댓값이다. 즉 이 상한은 배열 {@code maxItems}가 아니라
     * width·height 상한으로 이미 구조적으로 막혀 있다 — 정확히 상한만 확인한다.
     */
    @Test
    void tileLayerDataCellsBoundaryIsCappedByWidthTimesHeight() {
        ObjectNode project = projectWithTileLayerCells(10_000);
        assertDoesNotThrow(() -> validator.validateForDraft(
                project, GameTestSupport.write(project), GAME_ID, Map.of()));
    }

    @Test
    void assetsBoundary() {
        assertBoundary(300, this::projectWithAssets);
    }

    @Test
    void variablesBoundary() {
        assertBoundary(100, this::projectWithVariables);
    }

    @Test
    void itemsBoundary() {
        assertBoundary(100, this::projectWithItems);
    }

    @Test
    void dialogueNodesBoundary() {
        assertBoundary(300, this::projectWithDialogueNodes);
    }

    /** 정확히 상한은 통과, 상한+1은 거부 — FE {@code GAME_PROJECT_LIMITS}와 값이 같은지가 핵심이다. */
    private void assertBoundary(int limit, Function<Integer, ObjectNode> build) {
        ObjectNode atLimit = build.apply(limit);
        assertDoesNotThrow(() -> validator.validateForDraft(
                atLimit, GameTestSupport.write(atLimit), GAME_ID, Map.of()),
                () -> "정확히 상한(" + limit + ")은 통과해야 한다");

        ObjectNode overLimit = build.apply(limit + 1);
        assertThrows(GameValidationFailedException.class, () -> validator.validateForDraft(
                overLimit, GameTestSupport.write(overLimit), GAME_ID, Map.of()),
                () -> "상한+1(" + (limit + 1) + ")은 거부해야 한다");
    }

    // ── 상한별 프로젝트 빌더 ────────────────────────────────────────────

    private ObjectNode projectWithScenes(int count) {
        ObjectNode project = valid();
        ArrayNode scenes = project.putArray("scenes");
        for (int i = 0; i < count; i++) {
            scenes.add(minimalTopDownScene("scene" + i, 0, 0));
        }
        project.put("startSceneId", "scene0");
        return project;
    }

    private ObjectNode projectWithExtraSceneObjects(int count) {
        ObjectNode project = valid();
        ObjectNode scene = minimalTopDownScene("boundaryScene", count, 0);
        project.withArray("scenes").add(scene);
        return project;
    }

    private ObjectNode projectWithExtraSceneEvents(int count) {
        ObjectNode project = valid();
        ObjectNode scene = minimalTopDownScene("boundaryScene", 0, count);
        project.withArray("scenes").add(scene);
        return project;
    }

    private ObjectNode projectWithExtraSceneTileLayers(int count) {
        ObjectNode project = valid();
        ObjectNode scene = minimalTopDownScene("boundaryScene", 0, 0);
        ArrayNode tileLayers = scene.putArray("tileLayers");
        for (int i = 0; i < count; i++) {
            ObjectNode layer = tileLayers.addObject();
            layer.put("id", "layer" + i);
            layer.put("name", "레이어 " + i);
            layer.put("tilesetAssetId", "basicTiles");
            // data 길이는 scene width*height(4*4=16)와 정확히 같아야 한다(TILE_COUNT_INVALID).
            ArrayNode data = layer.putArray("data");
            for (int cell = 0; cell < 16; cell++) {
                data.add(0);
            }
        }
        project.withArray("scenes").add(scene);
        return project;
    }

    /** {@code count}는 항상 100*100(TOP_DOWN width·height 각각의 최댓값)과 같아야 한다. */
    private ObjectNode projectWithTileLayerCells(int count) {
        if (count != 100 * 100) {
            throw new IllegalArgumentException("width*height=10000 조합으로만 호출한다: " + count);
        }
        ObjectNode project = valid();
        ObjectNode scene = minimalTopDownScene("boundaryScene", 0, 0);
        scene.put("width", 100);
        scene.put("height", 100);
        ObjectNode layer = scene.putArray("tileLayers").addObject();
        layer.put("id", "layer0");
        layer.put("name", "레이어");
        layer.put("tilesetAssetId", "basicTiles");
        ArrayNode data = layer.putArray("data");
        for (int i = 0; i < count; i++) {
            data.add(0);
        }
        project.withArray("scenes").add(scene);
        return project;
    }

    private ObjectNode projectWithAssets(int count) {
        ObjectNode project = valid();
        ArrayNode assets = project.withArray("assets");
        for (int i = 0; i < count - 4; i++) {
            // 고정 fixture가 이미 4개(basicTiles·playerImage·keyImage·doorImage)를 갖고 있다 —
            // 그 자리를 감안해 필요한 만큼만 채운다.
            ObjectNode asset = assets.addObject();
            asset.put("id", "extraAsset" + i);
            asset.put("kind", "IMAGE");
            asset.put("source", "builtin://sprites/player");
        }
        return project;
    }

    private ObjectNode projectWithVariables(int count) {
        ObjectNode project = valid();
        // fixture의 "doorOpened"는 이벤트가 실제로 참조한다(VARIABLE_REFERENCE_NOT_FOUND) — 통째로
        // 갈아 끼우지 않고 그 자리를 감안해 나머지만 채운다.
        ArrayNode variables = project.withArray("variables");
        for (int i = 0; i < count - 1; i++) {
            ObjectNode variable = variables.addObject();
            variable.put("id", "var" + i);
            variable.put("type", "BOOLEAN");
            variable.put("initialValue", false);
        }
        return project;
    }

    private ObjectNode projectWithItems(int count) {
        ObjectNode project = valid();
        // fixture의 "key"는 오브젝트 PICKUP·이벤트 액션이 실제로 참조한다 — 그 자리를 감안한다.
        ArrayNode items = project.withArray("items");
        for (int i = 0; i < count - 1; i++) {
            ObjectNode item = items.addObject();
            item.put("id", "item" + i);
            item.put("name", "아이템 " + i);
        }
        return project;
    }

    private ObjectNode projectWithDialogueNodes(int count) {
        ObjectNode project = valid();
        ObjectNode scene = MAPPER.createObjectNode();
        scene.put("id", "boundaryDialogue");
        scene.put("type", "DIALOGUE");
        scene.put("name", "경계값 대화");
        scene.put("presentation", "OVERLAY");
        scene.put("startNodeId", "node0");
        ArrayNode nodes = scene.putArray("nodes");
        for (int i = 0; i < count; i++) {
            ObjectNode node = nodes.addObject();
            node.put("id", "node" + i);
            node.put("speaker", "안내");
            node.put("text", "텍스트");
            ObjectNode choice = node.putArray("choices").addObject();
            choice.put("id", "choice" + i);
            choice.put("text", "계속");
            choice.putArray("actions").addObject().put("type", "CLOSE_DIALOGUE");
        }
        project.withArray("scenes").add(scene);
        return project;
    }

    // ── 공용 빌더 ───────────────────────────────────────────────────────

    /**
     * TOP_DOWN Scene은 PLAYER_SPAWN이 정확히 1개여야 한다(다른 fixture 파일들이 이미 의존하는
     * 시맨틱 규칙) — 그래서 {@code objectCount}가 몇이든 그중 하나는 항상 PLAYER_SPAWN이고,
     * 나머지만 채우기용 DECORATION이다. 0을 넘기면 PLAYER_SPAWN 하나만 남는다(오브젝트 개수 자체가
     * 관심사가 아닌 event·tileLayer 경계 테스트용).
     */
    private ObjectNode minimalTopDownScene(String id, int objectCount, int eventCount) {
        ObjectNode scene = MAPPER.createObjectNode();
        scene.put("id", id);
        scene.put("type", "TOP_DOWN");
        scene.put("name", "경계값 Scene");
        scene.put("width", 4);
        scene.put("height", 4);
        scene.putArray("tileLayers");
        // id는 프로젝트 전체에서 유일해야 한다(DUPLICATE_OBJECT_ID) — Scene id를 접두어로 붙인다.
        ArrayNode objects = scene.putArray("objects");
        objects.add(minimalPlayerSpawn(id + "-obj0"));
        for (int i = 1; i < Math.max(objectCount, 1); i++) {
            objects.add(minimalDecoration(id + "-obj" + i));
        }
        ArrayNode events = scene.putArray("events");
        for (int i = 0; i < eventCount; i++) {
            ObjectNode event = events.addObject();
            event.put("id", "evt" + i);
            ObjectNode trigger = event.putObject("trigger");
            trigger.put("type", "ON_SCENE_START");
            event.putArray("conditions");
            event.putArray("actions").addObject().put("type", "COMPLETE_GAME");
        }
        return scene;
    }

    private ObjectNode minimalPlayerSpawn(String id) {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("id", id);
        object.put("preset", "PLAYER_SPAWN");
        ObjectNode position = object.putObject("position");
        position.put("x", 0);
        position.put("y", 0);
        object.put("visible", true);
        object.putArray("components");
        return object;
    }

    private ObjectNode minimalDecoration(String id) {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("id", id);
        object.put("preset", "DECORATION");
        ObjectNode position = object.putObject("position");
        position.put("x", 0);
        position.put("y", 0);
        object.put("visible", true);
        object.putArray("components");
        return object;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ObjectNode valid() {
        return GameTestSupport.validProjectFor(GAME_ID);
    }

    private ObjectNode worldScene(ObjectNode project) {
        for (com.fasterxml.jackson.databind.JsonNode scene : project.withArray("scenes")) {
            if (!"DIALOGUE".equals(scene.path("type").asText())) {
                return (ObjectNode) scene;
            }
        }
        throw new IllegalStateException("world Scene 이 fixture 에 없다");
    }

    private ObjectNode firstObject(ObjectNode project) {
        return (ObjectNode) worldScene(project).path("objects").get(0);
    }

    private ArrayNode components(ObjectNode object) {
        return (ArrayNode) object.path("components");
    }

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
}
