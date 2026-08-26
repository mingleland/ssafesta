package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether a GameProject may be stored or published (contracts §Draft 저장 · §Publish).
 *
 * <p>Two entry points, one body: Draft and Publish check the <b>same</b> structure, schema, limits
 * and reference integrity. Publish adds ownership, Dialogue policy and Asset policy on top. Spec 019
 * looked at letting Draft pass references with a warning and rejected it — the editor cannot create
 * a dangling reference in the first place (it refuses the deletion), so checking early costs nothing
 * and catches an editor bug at the save instead of at the next load (#48 2026-08-23).
 *
 * <p><b>Nothing here corrects anything.</b> No coordinate clamping, no unknown-field dropping, no
 * reference substitution (FR-034). A silent fix is how T-24 happened: the value the user chose
 * disappeared and nobody was told.
 */
@Component
public class GameProjectValidator {

    private static final String SCHEMA_RESOURCE = "/game/game-project-v1.schema.json";

    private final JsonSchema schema;

    public GameProjectValidator() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                // JSON Pointer, not JSONPath: GameSchemaRuleMap matches "/scenes/0/objects" and the
                // editor shows the same pointer, so both sides name a location the same way.
                .pathType(PathType.JSON_POINTER)
                .build();
        try (InputStream source = GameProjectValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (source == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " 가 클래스패스에 없습니다.");
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(source, config);
        } catch (java.io.IOException cause) {
            throw new IllegalStateException("GameProject 스키마를 읽을 수 없습니다.", cause);
        }
    }

    /**
     * Draft save. Every rule that Publish checks except ownership and the Publish-only policies.
     *
     * @throws GameValidationFailedException one or more rules broke; the exception carries all of them
     */
    public void validateForDraft(JsonNode project, String rawBody, Long gameId) {
        List<ApiErrorDetail> errors = commonErrors(project, rawBody, gameId);
        if (!errors.isEmpty()) {
            throw new GameValidationFailedException("게임을 저장할 수 없습니다.", errors, null);
        }
    }

    /**
     * Publish. The same set plus the policies that only matter once other people can start the game.
     *
     * <p>Runs against the stored Draft, not the request: Publish takes no project body, so what gets
     * frozen is what the server already holds (contracts §Publish).
     */
    public void validateForPublish(JsonNode project, String rawBody, Long gameId) {
        List<ApiErrorDetail> errors = commonErrors(project, rawBody, gameId);
        errors.addAll(publishOnlyErrors(project));
        if (!errors.isEmpty()) {
            throw new GameValidationFailedException("게임을 공개할 수 없습니다.", errors, null);
        }
    }

    private List<ApiErrorDetail> commonErrors(JsonNode project, String rawBody, Long gameId) {
        List<ApiErrorDetail> errors = new ArrayList<>();

        // Size first: a 20MB body would otherwise be walked in full before being refused.
        if (rawBody != null && GameProjectJson.utf8Size(rawBody) > GameProjectJson.MAX_PROJECT_BYTES) {
            errors.add(ApiErrorDetail.of("PROJECT_SIZE_INVALID",
                    "게임 데이터가 " + GameProjectJson.MAX_PROJECT_BYTES + " bytes 를 초과했습니다."));
            // No point reporting every other rule on a document this size.
            return errors;
        }

        errors.addAll(envelopeErrors(project, gameId));
        errors.addAll(schemaErrors(project));
        errors.addAll(assetSourceErrors(project));
        errors.addAll(duplicateIdErrors(project));
        errors.addAll(referenceErrors(project));
        errors.addAll(gameRulesErrors(project));
        return errors;
    }

    /**
     * The one {@code rules} check the schema cannot make (v1.1, #78).
     *
     * <p>{@code target} range and {@code playerDefeat} enum are already schema constraints and reach
     * their contract names through {@link GameSchemaRuleMap}. A repeated objective {@code type} is
     * not expressible: {@code uniqueItems} compares whole objects, so
     * {@code SCORE_AT_LEAST/100} and {@code SCORE_AT_LEAST/200} differ as objects while naming the
     * same goal twice — and the runtime has no defined answer for which target it should count.
     *
     * <p>Absent {@code rules} is not an error here. Whether it may be absent is the envelope's
     * question, and {@code RULES_PRESENCE_INVALID} already answers it in both directions.
     */
    private List<ApiErrorDetail> gameRulesErrors(JsonNode project) {
        List<ApiErrorDetail> errors = new ArrayList<>();
        JsonNode rules = project == null ? null : project.get("rules");
        if (rules == null || !rules.isObject()) {
            return errors;
        }
        JsonNode completion = rules.get("completion");
        if (completion == null || !completion.isObject()) {
            return errors;
        }
        Set<String> seen = new HashSet<>();
        List<JsonNode> objectives = GameProjectJson.arrayAt(completion, "objectives");
        for (int i = 0; i < objectives.size(); i++) {
            String type = GameProjectJson.textAt(objectives.get(i), "type");
            if (type != null && !seen.add(type)) {
                errors.add(ApiErrorDetail.of("DUPLICATE_OBJECTIVE_TYPE",
                        "/rules/completion/objectives/" + i + " 는 이미 쓰인 목표 유형입니다: " + type));
            }
        }
        return errors;
    }

    /**
     * The envelope fields the server owns rather than validates: the path is the truth for
     * {@code gameId}, and an unsupported {@code schemaVersion} is a different event from a broken one.
     */
    private List<ApiErrorDetail> envelopeErrors(JsonNode project, Long gameId) {
        List<ApiErrorDetail> errors = new ArrayList<>();

        String schemaVersion = GameProjectJson.textAt(project, "schemaVersion");
        if (schemaVersion != null && !GameProjectJson.SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            // A top-level code, not a rule inside GAME_VALIDATION_FAILED. The document may be
            // perfectly well formed for a version this server does not speak, and the client's fix is
            // to upgrade rather than to edit — which is why the fixture manifest expects
            // GAME_SCHEMA_UNSUPPORTED here. Thrown rather than collected: no other rule's verdict is
            // meaningful once the vocabulary is unknown.
            throw new com.example.ssafesta.common.ApiException(
                    com.example.ssafesta.common.ErrorCode.GAME_SCHEMA_UNSUPPORTED,
                    "지원하지 않는 게임 데이터 버전입니다: " + schemaVersion);
        }

        Long declaredGameId = GameProjectJson.longAt(project, "gameId");
        if (gameId != null && declaredGameId != null && !gameId.equals(declaredGameId)) {
            // Deliberately not corrected to the path value. FR-034 forbids rewriting what the user
            // sent, and a client that disagrees with the path has a bug worth surfacing.
            errors.add(ApiErrorDetail.of("MALFORMED_PROJECT",
                    "project.gameId 가 경로와 다릅니다. 경로가 정본입니다."));
        }
        return errors;
    }

    private List<ApiErrorDetail> schemaErrors(JsonNode project) {
        Set<ValidationMessage> messages = schema.validate(project);
        List<ApiErrorDetail> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            String location = message.getInstanceLocation() == null
                    ? "" : message.getInstanceLocation().toString();
            errors.add(ApiErrorDetail.of(
                    GameSchemaRuleMap.ruleFor(message.getType(), location),
                    location.isEmpty() ? "GameProject 문서 규칙 위반" : location + " 규칙 위반"));
        }
        return errors;
    }

    /**
     * {@code builtin://} or a server-issued stable {@code asset://} only.
     *
     * <p>The fixture validator's regex is {@code /^(builtin|asset):\/\//}, which lets
     * {@code asset://local/...} through. The server looks at the authority too — fixtures are the
     * floor and the server is the ceiling (contracts §Asset source). Draft is checked as well: #69
     * considered passing local sources with a warning and rejected it, and the editor never creates
     * one anyway.
     */
    private List<ApiErrorDetail> assetSourceErrors(JsonNode project) {
        List<ApiErrorDetail> errors = new ArrayList<>();
        List<JsonNode> assets = GameProjectJson.arrayAt(project, "assets");
        for (int i = 0; i < assets.size(); i++) {
            String source = GameProjectJson.textAt(assets.get(i), "source");
            if (source == null) {
                continue; // schema already reported the missing field
            }
            if (!isPersistableSource(source)) {
                errors.add(ApiErrorDetail.of("ASSET_SOURCE_INVALID",
                        "/assets/" + i + "/source 는 builtin:// 만 사용할 수 있습니다. "
                                + "사용자 에셋 업로드는 아직 제공되지 않습니다."));
            }
        }
        return errors;
    }

    /**
     * {@code builtin://} only, for now.
     *
     * <p>The contract's wording is "builtin:// or a <b>server-issued</b> stable asset://", and the
     * server issues none: §Asset Boundary keeps user upload out of the MVP entirely, and the endpoint
     * that would mint an {@code assetId} has no contract yet (#69). Accepting {@code asset://anything}
     * would let a project store a reference to something that cannot exist, which becomes a missing
     * image at play time rather than an error at save time.
     *
     * <p>So the count of valid {@code asset://} values is zero, and treating any of them as valid was
     * an accident: only {@code asset://local} was being refused. Nothing legitimate sends one today —
     * the editor's own preview scheme is {@code asset://local/...} and PR #72 already blocks those
     * before publish.
     *
     * <p>The relaxation belongs in the same commit that adds issuance (#69). Keeping both halves
     * together is what stops them from disagreeing.
     */
    private boolean isPersistableSource(String source) {
        return source.startsWith("builtin://");
    }

    /**
     * Ids must be unique within their own collection. The schema cannot say this — {@code uniqueItems}
     * compares whole objects, so two scenes differing only in title would pass while sharing an id.
     */
    private List<ApiErrorDetail> duplicateIdErrors(JsonNode project) {
        List<ApiErrorDetail> errors = new ArrayList<>();
        collectDuplicates(GameProjectJson.arrayAt(project, "scenes"), "DUPLICATE_SCENE_ID", "/scenes", errors);
        collectDuplicates(GameProjectJson.arrayAt(project, "items"), "DUPLICATE_ITEM_ID", "/items", errors);
        collectDuplicates(GameProjectJson.arrayAt(project, "variables"), "DUPLICATE_VARIABLE_ID", "/variables", errors);
        collectDuplicates(GameProjectJson.arrayAt(project, "assets"), "DUPLICATE_ASSET_ID", "/assets", errors);

        // Objects and Events live inside scenes but share <b>one namespace across the project</b>
        // (contracts README §v1 의미 검증 규칙: "각 namespace에서 중복되지 않는다"; the
        // duplicate-object-id fixture spells it out as "프로젝트 전체에서").
        //
        // Counting per scene was not merely looser — it contradicted the reference rules, which
        // resolve ids project-wide. Two objects called "door" in different scenes would both be
        // legal and "HIDE_OBJECT door" would have no defined meaning.
        List<JsonNode> scenes = GameProjectJson.arrayAt(project, "scenes");
        List<JsonNode> allObjects = new ArrayList<>();
        List<JsonNode> allEvents = new ArrayList<>();
        for (JsonNode scene : scenes) {
            allObjects.addAll(GameProjectJson.arrayAt(scene, "objects"));
            allEvents.addAll(GameProjectJson.arrayAt(scene, "events"));
        }
        collectDuplicates(allObjects, "DUPLICATE_OBJECT_ID", "/scenes/*/objects", errors);
        collectDuplicates(allEvents, "DUPLICATE_EVENT_ID", "/scenes/*/events", errors);

        // Dialogue nodes are the one id space that stays <b>scene-local</b>, and the contract says
        // so twice: README's namespace list names Scene·Object·Variable·Item·Event and stops there,
        // and both references that reach a node — the scene's own startNodeId and a choice's
        // nextNodeId — resolve inside the owning scene ("nextNodeId 는 같은 Scene 의 Node를 가리킨다").
        // Widening this to the project would reject two conversations that each open with "start".
        for (int s = 0; s < scenes.size(); s++) {
            collectDuplicates(GameProjectJson.arrayAt(scenes.get(s), "nodes"),
                    "DUPLICATE_DIALOGUE_NODE_ID", "/scenes/" + s + "/nodes", errors);
        }
        return errors;
    }

    private void collectDuplicates(List<JsonNode> nodes, String rule, String location,
                                   List<ApiErrorDetail> errors) {
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = GameProjectJson.textAt(node, "id");
            if (id != null && !seen.add(id)) {
                errors.add(ApiErrorDetail.of(rule, location + " 안에 중복된 id 가 있습니다: " + id));
            }
        }
    }

    /**
     * Every reference in the document points at something that exists (§참조 무결성 · §구조).
     *
     * <p>Checked on Draft as well as Publish (#48 2026-08-23). Delegated whole to
     * {@link GameReferenceRules}: sixteen rules share one index of ids, and keeping the walk here
     * would bury these entry points under it.
     */
    private List<ApiErrorDetail> referenceErrors(JsonNode project) {
        return GameReferenceRules.errors(project);
    }

    /**
     * Policies that only matter once other people can start the game.
     *
     * <p>Dialogue semantics are here rather than in the common set because a half-written
     * conversation is a normal thing to save and an abnormal thing to publish (contracts §rule).
     *
     * <p>{@code ASSET_KIND_UNSUPPORTED} refuses {@code AUDIO}: the schema allows the kind but v1 does
     * not take audio uploads, and #69 confirmed the server rejects it rather than storing something
     * no runtime will play.
     *
     * <p>Still absent: {@code ASSET_NOT_OWNED} and {@code COMPLETION_PATH_MISSING}. The first needs
     * server-side Asset registration to exist (#69), and the second needs the v1.1 {@code rules}
     * vocabulary approved (#78) — guessing either would refuse projects the contract permits.
     */
    private List<ApiErrorDetail> publishOnlyErrors(JsonNode project) {
        List<ApiErrorDetail> errors = new ArrayList<>(GameDialogueRules.errors(project));

        List<JsonNode> assets = GameProjectJson.arrayAt(project, "assets");
        for (int i = 0; i < assets.size(); i++) {
            if ("AUDIO".equals(GameProjectJson.textAt(assets.get(i), "kind"))) {
                errors.add(ApiErrorDetail.of("ASSET_KIND_UNSUPPORTED",
                        "/assets/" + i + "/kind — v1 은 오디오를 지원하지 않습니다."));
            }
        }

        if (!hasCompletionPath(project)) {
            errors.add(ApiErrorDetail.of("COMPLETION_PATH_MISSING",
                    "끝낼 수 있는 경로가 없습니다. COMPLETE_GAME Action 을 두거나 "
                            + "rules.completion.objectives 에 목표를 두어야 합니다."));
        }
        return errors;
    }

    /**
     * A published game must be finishable, and the ending must be <b>reachable</b>.
     *
     * <p>Either half satisfies it — at least one {@code rules.completion.objectives} entry, or a
     * {@code COMPLETE_GAME} action in a scene the player can actually get to. The two are the v1.1
     * and v1.0 ways of saying the same thing, so this does not wait on #78: a 1.0.0 project has no
     * {@code rules} at all and is judged entirely on the action, exactly as the contract's own
     * fixture is built.
     *
     * <p>Reachability is required by FR-067 ("rules 목표 또는 <b>도달 가능한</b> COMPLETE_GAME
     * Action"), not merely nice to have. Existence alone would pass a project whose only ending sits
     * in a scene nothing links to — finishable on paper, endless in front of a player.
     *
     * <p>What is <b>not</b> attempted is whether the conditions guarding that action can ever be
     * true. That is undecidable in general, and guessing would refuse projects the contract permits.
     *
     * <p>Publish-only. A draft with no ending yet is a normal thing to save.
     */
    private boolean hasCompletionPath(JsonNode project) {
        JsonNode rules = project.get("rules");
        if (rules != null && !GameProjectJson.arrayAt(rules.path("completion"), "objectives").isEmpty()) {
            return true;
        }
        for (JsonNode scene : reachableScenes(project)) {
            for (JsonNode event : GameProjectJson.arrayAt(scene, "events")) {
                if (containsCompleteGame(GameProjectJson.arrayAt(event, "actions"))) {
                    return true;
                }
            }
            for (JsonNode node : GameProjectJson.arrayAt(scene, "nodes")) {
                for (JsonNode choice : GameProjectJson.arrayAt(node, "choices")) {
                    if (containsCompleteGame(GameProjectJson.arrayAt(choice, "actions"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The scenes a player can arrive at, walking out from {@code startSceneId}.
     *
     * <p>Edges are {@code GO_TO_SCENE} and {@code SHOW_DIALOGUE} — the two actions that change what
     * the player is looking at. Conditions on the way are ignored: this asks whether a route exists
     * on the map, not whether the player can satisfy every gate along it.
     */
    private List<JsonNode> reachableScenes(JsonNode project) {
        java.util.Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        for (JsonNode scene : GameProjectJson.arrayAt(project, "scenes")) {
            String id = GameProjectJson.textAt(scene, "id");
            if (id != null) {
                byId.put(id, scene);
            }
        }

        List<JsonNode> reached = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        String start = GameProjectJson.textAt(project, "startSceneId");
        if (start != null) {
            queue.add(start);
        }

        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            JsonNode scene = byId.get(id);
            if (scene == null) {
                continue; // START_SCENE_NOT_FOUND / SCENE_REFERENCE_NOT_FOUND already reported it
            }
            reached.add(scene);
            for (String next : sceneTargets(scene)) {
                if (!seen.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return reached;
    }

    /** Every scene id this scene can hand control to. */
    private List<String> sceneTargets(JsonNode scene) {
        List<String> targets = new ArrayList<>();
        for (JsonNode event : GameProjectJson.arrayAt(scene, "events")) {
            collectSceneTargets(GameProjectJson.arrayAt(event, "actions"), targets);
        }
        for (JsonNode node : GameProjectJson.arrayAt(scene, "nodes")) {
            for (JsonNode choice : GameProjectJson.arrayAt(node, "choices")) {
                collectSceneTargets(GameProjectJson.arrayAt(choice, "actions"), targets);
            }
        }
        return targets;
    }

    private void collectSceneTargets(List<JsonNode> actions, List<String> targets) {
        for (JsonNode action : actions) {
            String type = GameProjectJson.textAt(action, "type");
            if (!"GO_TO_SCENE".equals(type) && !"SHOW_DIALOGUE".equals(type)) {
                continue;
            }
            String sceneId = GameProjectJson.textAt(action, "sceneId");
            if (sceneId != null) {
                targets.add(sceneId);
            }
        }
    }

    private boolean containsCompleteGame(List<JsonNode> actions) {
        for (JsonNode action : actions) {
            if ("COMPLETE_GAME".equals(GameProjectJson.textAt(action, "type"))) {
                return true;
            }
        }
        return false;
    }
}
