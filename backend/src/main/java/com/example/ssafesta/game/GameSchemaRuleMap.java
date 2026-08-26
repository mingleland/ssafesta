package com.example.ssafesta.game;

import java.util.regex.Pattern;

/**
 * Turns a JSON Schema violation into the contract's rule name.
 *
 * <p>This exists so the counted limits are enforced <b>once</b>. The schema already says
 * {@code maxItems: 50} on scenes and {@code maxItems: 500} on objects; re-checking those in Java
 * would mean two places to change and a chance for them to disagree — which is exactly how
 * {@code TileLayer.data.maxItems=10,000} and {@code PLATFORMER 200×100} drifted apart in #101.
 *
 * <p>Only the limits the contract <b>named</b> get a rule. Everything else the schema rejects is
 * {@code MALFORMED_PROJECT}: dialogue nodes 300, choices 6, objectives 5 and the rest are defined by
 * the schema alone (contracts §상한 말미). Inventing names for them would grow the rule vocabulary
 * past what any client agreed to branch on.
 */
final class GameSchemaRuleMap {

    /** {@code /scenes/3/objects} — the array whose length broke a cap. */
    private static final Pattern SCENES_OBJECTS = Pattern.compile("^/scenes/\\d+/objects$");
    private static final Pattern SCENES_EVENTS = Pattern.compile("^/scenes/\\d+/events$");
    private static final Pattern TILE_DATA = Pattern.compile("^/scenes/\\d+/tileLayers/\\d+/data$");
    private static final Pattern SCENE_GRAVITY = Pattern.compile("^/scenes/\\d+/gravity$");
    private static final Pattern OBJECTIVE_TARGET =
            Pattern.compile("^/rules/completion/objectives/\\d+/target$");

    private GameSchemaRuleMap() {
    }

    /**
     * @param keyword          the schema keyword that failed — {@code maxItems}, {@code pattern}, …
     * @param instanceLocation JSON Pointer into the document, e.g. {@code /scenes/0/objects/12/id}
     */
    static String ruleFor(String keyword, String instanceLocation) {
        String location = instanceLocation == null ? "" : instanceLocation;

        if ("maxItems".equals(keyword) || "minItems".equals(keyword)) {
            String counted = countRule(location);
            if (counted != null) {
                return counted;
            }
        }
        String bounded = boundRule(keyword, location);
        if (bounded != null) {
            return bounded;
        }
        if ("pattern".equals(keyword) && location.endsWith("/id")) {
            // Every id in the document goes through $defs/stableId, so one pattern failure on a
            // field named "id" is always the id format rule (contracts §구조).
            return "STABLE_ID_INVALID";
        }
        if ("enum".equals(keyword) && "/rules/playerDefeat".equals(location)) {
            return "PLAYER_DEFEAT_INVALID";
        }
        if (isRulesPresence(keyword, location)) {
            return "RULES_PRESENCE_INVALID";
        }
        return "MALFORMED_PROJECT";
    }

    /**
     * The two numeric ranges the contract gave a name to: PLATFORMER {@code gravity} 1~30 and
     * objective {@code target} 1~999,999,999.
     *
     * <p>{@code type} counts alongside {@code minimum}/{@code maximum} because the contract words
     * both as "정수 밖" — {@code gravity: 1.5} is outside the integers just as {@code 0} is outside
     * the range, and a client that branches on the rule name would otherwise get
     * {@code MALFORMED_PROJECT} for one half of the same sentence.
     *
     * <p>{@code objectives.maxItems=5} is deliberately absent: the contract sends that one to
     * {@code MALFORMED_PROJECT} (§rule 표 — "5개 초과는 이 rule이 아니라"), and it reaches it by
     * falling through here.
     */
    private static String boundRule(String keyword, String location) {
        if (!"minimum".equals(keyword) && !"maximum".equals(keyword) && !"type".equals(keyword)) {
            return null;
        }
        if (SCENE_GRAVITY.matcher(location).matches()) {
            return "PLATFORMER_GRAVITY_INVALID";
        }
        if (OBJECTIVE_TARGET.matcher(location).matches()) {
            return "OBJECTIVE_TARGET_INVALID";
        }
        return null;
    }

    private static String countRule(String location) {
        if ("/scenes".equals(location)) {
            return "SCENE_COUNT_INVALID";
        }
        if ("/assets".equals(location)) {
            return "ASSET_COUNT_INVALID";
        }
        if ("/variables".equals(location)) {
            return "VARIABLE_COUNT_INVALID";
        }
        if ("/items".equals(location)) {
            return "ITEM_COUNT_INVALID";
        }
        if (SCENES_OBJECTS.matcher(location).matches()) {
            return "OBJECT_COUNT_INVALID";
        }
        if (SCENES_EVENTS.matcher(location).matches()) {
            return "EVENT_COUNT_INVALID";
        }
        if (TILE_DATA.matcher(location).matches()) {
            return "TILE_COUNT_INVALID";
        }
        return null;
    }

    /**
     * The {@code allOf} pair that ties {@code rules} to the envelope version: {@code 1.1.0} requires
     * it, {@code 1.0.0} forbids it.
     *
     * <p>Both directions matter. If the server accepted {@code 1.0.0} with {@code rules}, the save
     * would succeed and the editor's own parser would throw on the next load — a failure that shows
     * up one screen later, with no test in between (#78).
     */
    private static boolean isRulesPresence(String keyword, String location) {
        if (!location.isEmpty()) {
            return false;
        }
        return "required".equals(keyword) || "not".equals(keyword) || "allOf".equals(keyword);
    }
}
