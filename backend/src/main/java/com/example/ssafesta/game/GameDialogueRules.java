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
 * The Dialogue policy, checked at Publish only (contracts §rule — "Publish가 추가로 보는 Dialogue 정책").
 *
 * <p>Draft skips these on purpose. A conversation half-written is a normal state to save, and
 * refusing it would mean the editor could not persist work in progress; a conversation half-written
 * that other people can walk into is not.
 *
 * <p>None of this is expressible in JSON Schema: every rule here compares one part of the document
 * against another.
 */
final class GameDialogueRules {

    /**
     * Actions that end a dispatch (event-runtime-semantics §Action). Anything after one of these is
     * dead code the runtime will never reach, which is why it is refused rather than ignored.
     */
    private static final Set<String> TERMINAL_ACTIONS =
            Set.of("SHOW_DIALOGUE", "CLOSE_DIALOGUE", "GO_TO_SCENE", "COMPLETE_GAME");

    private GameDialogueRules() {
    }

    static List<ApiErrorDetail> errors(JsonNode project) {
        List<ApiErrorDetail> errors = new ArrayList<>();
        List<JsonNode> scenes = GameProjectJson.arrayAt(project, "scenes");

        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode scene : scenes) {
            String id = GameProjectJson.textAt(scene, "id");
            if (id != null) {
                byId.put(id, scene);
            }
        }

        for (int s = 0; s < scenes.size(); s++) {
            JsonNode scene = scenes.get(s);
            String base = "/scenes/" + s;
            // Only a choice inside an OVERLAY dialogue may close one. World-scene events run with no
            // overlay open, and a FULL_SCREEN dialogue is the scene rather than a layer over it
            // (contracts README: "CLOSE_DIALOGUE 는 OVERLAY DIALOGUE Choice 에서만 허용").
            boolean overlayDialogue = "DIALOGUE".equals(GameProjectJson.textAt(scene, "type"))
                    && "OVERLAY".equals(GameProjectJson.textAt(scene, "presentation"));

            // Events live on world scenes; their actions are checked for ordering and dialogue targets.
            List<JsonNode> events = GameProjectJson.arrayAt(scene, "events");
            for (int e = 0; e < events.size(); e++) {
                checkActions(GameProjectJson.arrayAt(events.get(e), "actions"),
                        base + "/events/" + e + "/actions", byId, false, errors);
            }

            if (!"DIALOGUE".equals(GameProjectJson.textAt(scene, "type"))) {
                continue;
            }
            checkDialogueScene(scene, base, byId, overlayDialogue, errors);
        }
        return errors;
    }

    private static void checkDialogueScene(JsonNode scene, String base, Map<String, JsonNode> byId,
                                           boolean overlayDialogue, List<ApiErrorDetail> errors) {
        List<JsonNode> nodes = GameProjectJson.arrayAt(scene, "nodes");
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = GameProjectJson.textAt(node, "id");
            if (id != null) {
                nodeIds.add(id);
            }
        }

        String startNodeId = GameProjectJson.textAt(scene, "startNodeId");
        if (startNodeId != null && !nodeIds.contains(startNodeId)) {
            errors.add(ApiErrorDetail.of("DIALOGUE_START_NODE_NOT_FOUND",
                    base + "/startNodeId 가 없는 노드를 가리킵니다: " + startNodeId));
        }

        for (int n = 0; n < nodes.size(); n++) {
            List<JsonNode> choices = GameProjectJson.arrayAt(nodes.get(n), "choices");
            for (int c = 0; c < choices.size(); c++) {
                JsonNode choice = choices.get(c);
                String where = base + "/nodes/" + n + "/choices/" + c;
                List<JsonNode> actions = GameProjectJson.arrayAt(choice, "actions");

                String nextNodeId = GameProjectJson.textAt(choice, "nextNodeId");
                if (nextNodeId != null && !nodeIds.contains(nextNodeId)) {
                    errors.add(ApiErrorDetail.of("NEXT_DIALOGUE_NODE_NOT_FOUND",
                            where + "/nextNodeId 가 없는 노드를 가리킵니다: " + nextNodeId));
                }
                if (nextNodeId != null && hasTerminal(actions)) {
                    // Both would mean two different next steps for one click, and the runtime lets the
                    // terminal action win — so the nextNodeId is a lie about what happens
                    // (event-runtime-semantics §103).
                    errors.add(ApiErrorDetail.of("DIALOGUE_NEXT_WITH_TERMINAL_ACTION",
                            where + " 는 nextNodeId 와 terminal Action 을 함께 가질 수 없습니다."));
                }
                checkActions(actions, where + "/actions", byId, overlayDialogue, errors);
            }
        }
    }

    /**
     * @param mayCloseDialogue true only inside a choice of an {@code OVERLAY} dialogue — the one
     *                         place where an overlay is open and therefore closable
     */
    private static void checkActions(List<JsonNode> actions, String where, Map<String, JsonNode> byId,
                                     boolean mayCloseDialogue, List<ApiErrorDetail> errors) {
        for (int a = 0; a < actions.size(); a++) {
            JsonNode action = actions.get(a);
            String type = GameProjectJson.textAt(action, "type");
            if (type == null) {
                continue; // schema already reported it
            }
            String at = where + "/" + a;

            if (TERMINAL_ACTIONS.contains(type) && a != actions.size() - 1) {
                errors.add(ApiErrorDetail.of("TERMINAL_ACTION_NOT_LAST",
                        at + " 뒤에 Action 이 더 있습니다. terminal Action 은 마지막이어야 합니다."));
            }
            if ("SHOW_DIALOGUE".equals(type)) {
                checkDialogueTarget(action, at, byId, errors);
            }
            if ("CLOSE_DIALOGUE".equals(type) && !mayCloseDialogue) {
                errors.add(ApiErrorDetail.of("DIALOGUE_CLOSE_CONTEXT_INVALID",
                        at + " — 닫을 Overlay 가 없습니다. CLOSE_DIALOGUE 는 OVERLAY DIALOGUE 의 "
                                + "선택지에서만 쓸 수 있습니다."));
            }
        }
    }

    /**
     * {@code SHOW_DIALOGUE} must name an {@code OVERLAY} DIALOGUE scene.
     *
     * <p>A {@code TOP_DOWN} target would put the player in a world scene with no way back, and a
     * {@code FULL_SCREEN} one is entered with {@code GO_TO_SCENE} instead — the two are different
     * transitions and mixing them loses the return path.
     */
    private static void checkDialogueTarget(JsonNode action, String at, Map<String, JsonNode> byId,
                                            List<ApiErrorDetail> errors) {
        String sceneId = GameProjectJson.textAt(action, "sceneId");
        if (sceneId == null) {
            return;
        }
        JsonNode target = byId.get(sceneId);
        if (target == null) {
            errors.add(ApiErrorDetail.of("SCENE_REFERENCE_NOT_FOUND",
                    at + "/sceneId 가 없는 Scene 을 가리킵니다: " + sceneId));
            return;
        }
        boolean overlayDialogue = "DIALOGUE".equals(GameProjectJson.textAt(target, "type"))
                && "OVERLAY".equals(GameProjectJson.textAt(target, "presentation"));
        if (!overlayDialogue) {
            errors.add(ApiErrorDetail.of("DIALOGUE_TARGET_INVALID",
                    at + "/sceneId 는 OVERLAY DIALOGUE Scene 이어야 합니다: " + sceneId));
        }
    }

    private static boolean hasTerminal(List<JsonNode> actions) {
        for (JsonNode action : actions) {
            if (TERMINAL_ACTIONS.contains(GameProjectJson.textAt(action, "type"))) {
                return true;
            }
        }
        return false;
    }
}
