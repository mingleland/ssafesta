package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every "does the thing it points at exist" rule (contracts §참조 무결성 · §구조).
 *
 * <p>Checked on <b>Draft as well as Publish</b> (#48 2026-08-23). The alternative — passing dangling
 * references on save with a warning — was considered and rejected: the editor refuses the deletion
 * that would create one, so a dangling reference means the editor has a bug, and catching it at the
 * save names the moment it appeared. Left to Publish it would surface much later, and left to the
 * runtime it would surface in front of a player.
 *
 * <p>Ids are unique <b>project-wide</b> (contracts README: "각 namespace에서 중복되지 않는다"), so an
 * action naming an object resolves without knowing which scene it sits in — a dialogue choice hiding
 * a door in the world scene is an ordinary thing to build.
 *
 * <p><b>Triggers are the exception.</b> {@code ON_ENTER} and {@code ON_INTERACT} are fixed to "같은
 * TOP_DOWN/PLATFORMER Scene Object" because events run only while their own scene is current; one
 * aimed elsewhere can never fire, so it is dead configuration rather than a working cross-reference.
 */
final class GameReferenceRules {

    private GameReferenceRules() {
    }

    static List<ApiErrorDetail> errors(JsonNode project) {
        Index index = Index.of(project);
        List<ApiErrorDetail> errors = new ArrayList<>();

        String startSceneId = GameProjectJson.textAt(project, "startSceneId");
        if (startSceneId != null && !index.sceneIds.contains(startSceneId)) {
            errors.add(ApiErrorDetail.of("START_SCENE_NOT_FOUND",
                    "/startSceneId 가 없는 Scene 을 가리킵니다: " + startSceneId));
        } else if (index.overlayDialogueIds.contains(startSceneId)) {
            // An OVERLAY draws on top of the scene that opened it, and CLOSE_DIALOGUE returns there.
            // Starting inside one means there is nothing underneath and nowhere to return to
            // (contracts README, event-runtime-semantics Dialogue Overlay).
            errors.add(ApiErrorDetail.of("DIALOGUE_PRESENTATION_INVALID",
                    "/startSceneId 는 OVERLAY DIALOGUE 일 수 없습니다: " + startSceneId));
        }

        List<JsonNode> items = GameProjectJson.arrayAt(project, "items");
        for (int i = 0; i < items.size(); i++) {
            String assetId = GameProjectJson.textAt(items.get(i), "assetId");
            if (assetId != null && !index.assetKinds.containsKey(assetId)) {
                errors.add(ApiErrorDetail.of("ITEM_ASSET_NOT_FOUND",
                        "/items/" + i + "/assetId 가 없는 Asset 을 가리킵니다: " + assetId));
            }
        }

        List<JsonNode> variables = GameProjectJson.arrayAt(project, "variables");
        for (int i = 0; i < variables.size(); i++) {
            checkInitialValue(variables.get(i), "/variables/" + i, errors);
        }

        List<JsonNode> scenes = GameProjectJson.arrayAt(project, "scenes");
        for (int s = 0; s < scenes.size(); s++) {
            checkScene(scenes.get(s), "/scenes/" + s, index, errors);
        }
        return errors;
    }

    private static void checkScene(JsonNode scene, String base, Index index,
                                   List<ApiErrorDetail> errors) {
        String type = GameProjectJson.textAt(scene, "type");

        checkAsset(GameProjectJson.textAt(scene, "backgroundAssetId"), "IMAGE",
                base + "/backgroundAssetId", "BACKGROUND_ASSET_INVALID", index, errors);

        if ("DIALOGUE".equals(type)) {
            checkDialogueNodes(scene, base, index, errors);
            return;
        }

        Integer width = intAt(scene, "width");
        Integer height = intAt(scene, "height");
        checkTileLayers(scene, base, width, height, index, errors);
        checkObjects(scene, base, width, height, index, errors);

        // Triggers resolve within their own scene, unlike actions. event-runtime-semantics fixes
        // ON_ENTER and ON_INTERACT targets as the same TOP_DOWN/PLATFORMER scene object: events run
        // only while their scene is current, so one aimed at another scene can never fire.
        Set<String> localObjectIds = new HashSet<>();
        for (JsonNode object : GameProjectJson.arrayAt(scene, "objects")) {
            String id = GameProjectJson.textAt(object, "id");
            if (id != null) {
                localObjectIds.add(id);
            }
        }
        checkEvents(scene, base, localObjectIds, index, errors);
    }

    private static void checkTileLayers(JsonNode scene, String base, Integer width, Integer height,
                                        Index index, List<ApiErrorDetail> errors) {
        List<JsonNode> layers = GameProjectJson.arrayAt(scene, "tileLayers");
        for (int t = 0; t < layers.size(); t++) {
            JsonNode layer = layers.get(t);
            String where = base + "/tileLayers/" + t;
            checkAsset(GameProjectJson.textAt(layer, "tilesetAssetId"), "TILESET",
                    where + "/tilesetAssetId", "TILESET_ASSET_INVALID", index, errors);

            // The schema caps the array at 10,000 but cannot say it must be exactly width × height —
            // a layer one row short renders a torn map rather than failing, so nobody would notice.
            List<JsonNode> data = GameProjectJson.arrayAt(layer, "data");
            if (width != null && height != null && data.size() != width * height) {
                errors.add(ApiErrorDetail.of("TILE_COUNT_INVALID",
                        where + "/data 길이가 " + width + "×" + height + "=" + (width * height)
                                + " 이어야 하는데 " + data.size() + " 입니다."));
            }
        }
    }

    private static void checkObjects(JsonNode scene, String base, Integer width, Integer height,
                                     Index index, List<ApiErrorDetail> errors) {
        List<JsonNode> objects = GameProjectJson.arrayAt(scene, "objects");
        int spawns = 0;

        for (int o = 0; o < objects.size(); o++) {
            JsonNode object = objects.get(o);
            String where = base + "/objects/" + o;
            if ("PLAYER_SPAWN".equals(GameProjectJson.textAt(object, "preset"))) {
                spawns++;
            }

            JsonNode position = object.get("position");
            Integer x = position == null ? null : intAt(position, "x");
            Integer y = position == null ? null : intAt(position, "y");
            if (width != null && height != null && x != null && y != null
                    && (x >= width || y >= height)) {
                // Refused, never clamped. A silently moved object is FR-034's whole subject and the
                // shape of T-24 — the placement the user chose disappears without a word.
                errors.add(ApiErrorDetail.of("OBJECT_POSITION_INVALID",
                        where + "/position 이 Scene 격자(" + width + "×" + height + ") 밖입니다."));
            }

            checkComponents(object, where, index, errors);
        }

        // DIALOGUE scenes have no player, which is why this runs only on world scenes.
        if (spawns != 1) {
            errors.add(ApiErrorDetail.of("PLAYER_SPAWN_COUNT_INVALID",
                    base + "/objects 에 PLAYER_SPAWN 이 정확히 1개여야 하는데 " + spawns + "개입니다."));
        }
    }

    private static void checkComponents(JsonNode object, String where, Index index,
                                        List<ApiErrorDetail> errors) {
        Set<String> seenTypes = new HashSet<>();
        List<JsonNode> components = GameProjectJson.arrayAt(object, "components");
        for (int c = 0; c < components.size(); c++) {
            JsonNode component = components.get(c);
            String type = GameProjectJson.textAt(component, "type");
            String at = where + "/components/" + c;
            if (type == null) {
                continue;
            }
            if (!seenTypes.add(type)) {
                // Two of the same kind on one object have no defined precedence — the runtime would
                // pick one and the editor would show the other.
                errors.add(ApiErrorDetail.of("DUPLICATE_COMPONENT_TYPE",
                        at + " — 한 Object 에 " + type + " 컴포넌트가 두 번 있습니다."));
            }
            switch (type) {
                case "SPRITE" -> checkAsset(GameProjectJson.textAt(component, "assetId"), "IMAGE",
                        at + "/assetId", "SPRITE_ASSET_INVALID", index, errors);
                // Projectiles and spawned enemies are images too; the contract has no separate rule
                // for them, and reporting them under the sprite rule keeps the vocabulary closed.
                // The contract names these two separately from SPRITE_ASSET_INVALID (§참조 무결성).
                // One name for all three would tell the editor "an image reference is wrong"
                // without saying which of a SHOOTER's two asset slots it is.
                case "SHOOTER" -> checkAsset(GameProjectJson.textAt(component, "projectileAssetId"),
                        "IMAGE", at + "/projectileAssetId", "PROJECTILE_ASSET_INVALID", index, errors);
                case "SPAWNER" -> checkAsset(GameProjectJson.textAt(component, "enemyAssetId"),
                        "IMAGE", at + "/enemyAssetId", "SPAWNER_ASSET_INVALID", index, errors);
                case "PICKUP" -> {
                    String itemId = GameProjectJson.textAt(component, "itemId");
                    if (itemId != null && !index.itemIds.contains(itemId)) {
                        errors.add(ApiErrorDetail.of("PICKUP_ITEM_NOT_FOUND",
                                at + "/itemId 가 없는 아이템을 가리킵니다: " + itemId));
                    }
                }
                default -> { }
            }
        }
    }

    private static void checkEvents(JsonNode scene, String base, Set<String> localObjectIds,
                                    Index index, List<ApiErrorDetail> errors) {
        List<JsonNode> events = GameProjectJson.arrayAt(scene, "events");
        for (int e = 0; e < events.size(); e++) {
            JsonNode event = events.get(e);
            String where = base + "/events/" + e;

            JsonNode trigger = event.get("trigger");
            String targetId = trigger == null ? null : GameProjectJson.textAt(trigger, "targetId");
            if (targetId != null && !localObjectIds.contains(targetId)) {
                String reason = index.objectIds.contains(targetId)
                        ? " 는 다른 Scene 의 Object 입니다. Trigger 는 같은 Scene 만 가리킵니다: "
                        : " 가 없는 Object 를 가리킵니다: ";
                errors.add(ApiErrorDetail.of("TRIGGER_TARGET_NOT_FOUND",
                        where + "/trigger/targetId" + reason + targetId));
            }

            checkConditions(GameProjectJson.arrayAt(event, "conditions"), where + "/conditions",
                    index, errors);
            checkActions(GameProjectJson.arrayAt(event, "actions"), where + "/actions", index, errors);
        }
    }

    private static void checkDialogueNodes(JsonNode scene, String base, Index index,
                                           List<ApiErrorDetail> errors) {
        List<JsonNode> nodes = GameProjectJson.arrayAt(scene, "nodes");
        for (int n = 0; n < nodes.size(); n++) {
            JsonNode node = nodes.get(n);
            String where = base + "/nodes/" + n;
            checkAsset(GameProjectJson.textAt(node, "portraitAssetId"), "IMAGE",
                    where + "/portraitAssetId", "PORTRAIT_ASSET_INVALID", index, errors);

            List<JsonNode> choices = GameProjectJson.arrayAt(node, "choices");
            for (int c = 0; c < choices.size(); c++) {
                String at = where + "/choices/" + c;
                checkConditions(GameProjectJson.arrayAt(choices.get(c), "conditions"),
                        at + "/conditions", index, errors);
                checkActions(GameProjectJson.arrayAt(choices.get(c), "actions"),
                        at + "/actions", index, errors);
            }
        }
    }

    private static void checkConditions(List<JsonNode> conditions, String where, Index index,
                                        List<ApiErrorDetail> errors) {
        for (int i = 0; i < conditions.size(); i++) {
            JsonNode condition = conditions.get(i);
            String at = where + "/" + i;
            checkVariable(condition, at, index, errors);
            checkItem(GameProjectJson.textAt(condition, "itemId"), at, index, errors);
        }
    }

    private static void checkActions(List<JsonNode> actions, String where, Index index,
                                     List<ApiErrorDetail> errors) {
        for (int i = 0; i < actions.size(); i++) {
            JsonNode action = actions.get(i);
            String at = where + "/" + i;

            String sceneId = GameProjectJson.textAt(action, "sceneId");
            if (sceneId != null && !index.sceneIds.contains(sceneId)) {
                errors.add(ApiErrorDetail.of("SCENE_REFERENCE_NOT_FOUND",
                        at + "/sceneId 가 없는 Scene 을 가리킵니다: " + sceneId));
            } else if ("GO_TO_SCENE".equals(GameProjectJson.textAt(action, "type"))
                    && index.overlayDialogueIds.contains(sceneId)) {
                // GO_TO_SCENE replaces the current scene, and an OVERLAY has no scene of its own to
                // stand on. SHOW_DIALOGUE is the transition that opens one.
                errors.add(ApiErrorDetail.of("DIALOGUE_PRESENTATION_INVALID",
                        at + "/sceneId 는 OVERLAY DIALOGUE 일 수 없습니다. SHOW_DIALOGUE 를 쓰십시오: "
                                + sceneId));
            }
            String objectId = GameProjectJson.textAt(action, "objectId");
            if (objectId != null && !index.objectIds.contains(objectId)) {
                errors.add(ApiErrorDetail.of("OBJECT_REFERENCE_NOT_FOUND",
                        at + "/objectId 가 없는 Object 를 가리킵니다: " + objectId));
            }
            checkVariable(action, at, index, errors);
            checkItem(GameProjectJson.textAt(action, "itemId"), at, index, errors);
        }
    }

    /**
     * The variable exists, and the scalar written next to it is of the declared type.
     *
     * <p>{@code event-runtime-semantics} compares with <b>strict equality and no conversion</b>, so
     * {@code VARIABLE_EQUALS} on an INTEGER against {@code "3"} is never true and {@code SET_VARIABLE}
     * of a STRING into it corrupts every later comparison. The contract already names this exact
     * check for a variable's {@code initialValue}; leaving assignment and comparison unchecked was
     * the gap, and the editor's own validator (`gameProject.ts`) has been refusing both all along.
     *
     * <p>Only {@code VARIABLE_EQUALS} and {@code SET_VARIABLE} carry both fields. A {@code SCORE_VALUE}
     * component has a {@code value} and no variable, so it never reaches the comparison.
     */
    private static void checkVariable(JsonNode holder, String at, Index index,
                                      List<ApiErrorDetail> errors) {
        String variableId = GameProjectJson.textAt(holder, "variableId");
        if (variableId == null) {
            return;
        }
        String declared = index.variableTypes.get(variableId);
        if (declared == null) {
            errors.add(ApiErrorDetail.of("VARIABLE_REFERENCE_NOT_FOUND",
                    at + "/variableId 가 없는 변수를 가리킵니다: " + variableId));
            // Nothing to compare the value against — reporting a type mismatch on top would be a
            // second error about the same missing declaration.
            return;
        }
        JsonNode value = holder.get("value");
        if (value != null && !scalarMatches(declared, value)) {
            errors.add(ApiErrorDetail.of("VARIABLE_VALUE_TYPE_INVALID",
                    at + "/value 가 변수 " + variableId + " 의 선언 타입 " + declared + " 과 맞지 않습니다."));
        }
    }

    /** BOOLEAN·INTEGER·STRING against a JSON scalar. Unknown types pass — the schema owns the enum. */
    private static boolean scalarMatches(String type, JsonNode value) {
        return switch (type) {
            case "BOOLEAN" -> value.isBoolean();
            case "INTEGER" -> value.isIntegralNumber();
            case "STRING" -> value.isTextual();
            default -> true;
        };
    }

    private static void checkItem(String itemId, String at, Index index,
                                  List<ApiErrorDetail> errors) {
        if (itemId != null && !index.itemIds.contains(itemId)) {
            errors.add(ApiErrorDetail.of("ITEM_REFERENCE_NOT_FOUND",
                    at + "/itemId 가 없는 아이템을 가리킵니다: " + itemId));
        }
    }

    /**
     * Existence <b>and</b> kind. A tile layer pointing at an {@code IMAGE} resolves to a file that
     * loads and then tiles wrongly, which is harder to diagnose than a missing one.
     */
    private static void checkAsset(String assetId, String expectedKind, String at, String rule,
                                   Index index, List<ApiErrorDetail> errors) {
        if (assetId == null) {
            return;
        }
        String kind = index.assetKinds.get(assetId);
        if (kind == null) {
            errors.add(ApiErrorDetail.of(rule, at + " 가 없는 Asset 을 가리킵니다: " + assetId));
            return;
        }
        if (!expectedKind.equals(kind)) {
            errors.add(ApiErrorDetail.of(rule,
                    at + " 는 " + expectedKind + " Asset 이어야 하는데 " + kind + " 입니다: " + assetId));
        }
    }

    /** A declared {@code INTEGER} holding {@code "3"} is a runtime comparison that never matches. */
    private static void checkInitialValue(JsonNode variable, String where,
                                          List<ApiErrorDetail> errors) {
        String type = GameProjectJson.textAt(variable, "type");
        JsonNode value = variable.get("initialValue");
        if (type == null || value == null) {
            return;
        }
        if (!scalarMatches(type, value)) {
            errors.add(ApiErrorDetail.of("VARIABLE_INITIAL_VALUE_INVALID",
                    where + "/initialValue 가 선언 타입 " + type + " 과 맞지 않습니다."));
        }
    }

    private static Integer intAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() ? value.asInt() : null;
    }

    /** Everything a reference may point at, gathered once so the walk below stays linear. */
    private record Index(Set<String> sceneIds, Set<String> objectIds, Set<String> itemIds,
                         Set<String> overlayDialogueIds,
                         Map<String, String> variableTypes, Map<String, String> assetKinds) {

        static Index of(JsonNode project) {
            Set<String> sceneIds = new HashSet<>();
            Set<String> objectIds = new HashSet<>();
            Set<String> itemIds = new HashSet<>();
            Set<String> overlayDialogueIds = new HashSet<>();
            Map<String, String> variableTypes = new HashMap<>();
            Map<String, String> assetKinds = new HashMap<>();

            for (JsonNode asset : GameProjectJson.arrayAt(project, "assets")) {
                put(assetKinds, GameProjectJson.textAt(asset, "id"),
                        GameProjectJson.textAt(asset, "kind"));
            }
            for (JsonNode variable : GameProjectJson.arrayAt(project, "variables")) {
                put(variableTypes, GameProjectJson.textAt(variable, "id"),
                        GameProjectJson.textAt(variable, "type"));
            }
            for (JsonNode item : GameProjectJson.arrayAt(project, "items")) {
                add(itemIds, GameProjectJson.textAt(item, "id"));
            }
            for (JsonNode scene : GameProjectJson.arrayAt(project, "scenes")) {
                String sceneId = GameProjectJson.textAt(scene, "id");
                add(sceneIds, sceneId);
                if ("DIALOGUE".equals(GameProjectJson.textAt(scene, "type"))
                        && "OVERLAY".equals(GameProjectJson.textAt(scene, "presentation"))) {
                    add(overlayDialogueIds, sceneId);
                }
                for (JsonNode object : GameProjectJson.arrayAt(scene, "objects")) {
                    add(objectIds, GameProjectJson.textAt(object, "id"));
                }
            }
            return new Index(sceneIds, objectIds, itemIds, overlayDialogueIds,
                    variableTypes, assetKinds);
        }

        private static void add(Set<String> target, String value) {
            if (value != null) {
                target.add(value);
            }
        }

        private static void put(Map<String, String> target, String key, String value) {
            if (key != null && value != null) {
                target.put(key, value);
            }
        }
    }
}
