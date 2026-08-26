package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** Game Studio API (spec 019 contracts/game-api.md v1.0). */
@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameDraftService draftService;
    private final GamePublishService publishService;
    private final GamePublishedQueryService publishedQueries;
    private final GameLifecycleService lifecycle;

    public GameController(GameDraftService draftService, GamePublishService publishService,
                          GamePublishedQueryService publishedQueries,
                          GameLifecycleService lifecycle) {
        this.draftService = draftService;
        this.publishService = publishService;
        this.publishedQueries = publishedQueries;
        this.lifecycle = lifecycle;
    }

    /** {@code 201} + {@code Location}. Creates no draft — the editor first-saves its starter project. */
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<GameLifecycleService.GameSummary> create(@AuthenticationPrincipal Jwt jwt,
                                                                  @RequestBody String body) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        GameLifecycleService.GameSummary created = lifecycle.create(userId, CreateRequest.readTitle(body));
        return ResponseEntity.created(java.net.URI.create("/api/v1/games/" + created.gameId()))
                .body(created);
    }

    /**
     * The caller's own games, soft-deleted ones included.
     *
     * <p>A path rather than {@code ?mine=true}: {@code GET /booths/mine} already set that shape, and a
     * query flag keeps inviting the question "what does dropping it return" — a full public catalogue
     * is a different feature with pagination and search, and not v1's.
     */
    @GetMapping("/mine")
    @SecurityRequirement(name = "bearerAuth")
    public MineResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return new MineResponse(lifecycle.listOwned(GamePrincipal.requireMemberId(jwt)));
    }

    @PatchMapping("/{gameId}")
    @SecurityRequirement(name = "bearerAuth")
    public GameLifecycleService.GameSummary changeVisibility(@AuthenticationPrincipal Jwt jwt,
                                                            @PathVariable Long gameId,
                                                            @RequestBody String body) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        return lifecycle.changeVisibility(gameId, userId, VisibilityRequest.read(body));
    }

    /**
     * Ordinary deletion — soft. {@code 204}.
     *
     * <p>A repeat answers {@code 404 GAME_DELETED}, and the contract tells clients to read that as
     * "already done" rather than as a failure: a lost response retries into exactly that branch.
     */
    @DeleteMapping("/{gameId}")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long gameId) {
        lifecycle.softDelete(gameId, GamePrincipal.requireMemberId(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Restore. Takes a live slot, so it passes the same cap creation does. */
    @PostMapping("/{gameId}/restore")
    @SecurityRequirement(name = "bearerAuth")
    public GameLifecycleService.GameSummary restore(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable Long gameId) {
        return lifecycle.restore(gameId, GamePrincipal.requireMemberId(jwt));
    }

    /**
     * {@code 204} when the game has never been saved — the editor keeps its starter project.
     *
     * <p>Not {@code 404}: that is reserved for "no such game", and collapsing the two would make the
     * editor open in an error state on every new game (#104 ①).
     */
    @GetMapping("/{gameId}/draft")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DraftResponse> draft(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable Long gameId) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        return draftService.findDraft(gameId, userId)
                .map(view -> ResponseEntity.ok(DraftResponse.of(view)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Saves the working copy.
     *
     * <p>The body is taken as text: Spring's ObjectMapper silently drops unknown fields, and this API
     * refuses them instead ({@code MALFORMED_PROJECT} via {@code additionalProperties}). Binding to a
     * DTO would make the strictest rule in the contract impossible to enforce.
     */
    @PutMapping(value = "/{gameId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    public DraftResponse saveDraft(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable Long gameId,
                                   @RequestBody String body) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        SaveRequest request = SaveRequest.read(body);
        return DraftResponse.of(
                draftService.save(gameId, userId, request.expectedRevision(), request.projectJson()));
    }

    @PostMapping("/{gameId}/publish")
    @SecurityRequirement(name = "bearerAuth")
    public PublishResponse publish(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable Long gameId,
                                   @RequestBody String body) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        GamePublishService.PublishOutcome outcome =
                publishService.publish(gameId, userId, PublishRequest.read(body));
        return new PublishResponse(outcome.gameId(), outcome.publishedVersion(),
                outcome.publishedAt(), outcome.warnings());
    }

    /**
     * The public snapshot. Open to guests — playing a published game is theirs too (FR-023).
     *
     * <p>{@code no-cache} rather than a long {@code max-age}: this URL follows the current pointer, so
     * re-publishing has to become visible at once. ETag revalidation keeps the 2MB body off the wire
     * when nothing moved. A long {@code max-age, immutable} belongs on a version-pinned URL, which is
     * a later addition (contracts §Runtime).
     */
    @GetMapping("/{gameId}/published")
    public ResponseEntity<PublishedResponse> published(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable Long gameId,
                                                       WebRequest webRequest) {
        Long viewerUserId = GamePrincipal.optionalMemberId(jwt);

        // Resolved before the snapshot is read. The gates run first — a game that went private since
        // the client last saw it must be refused rather than answered "unchanged" — and an unchanged
        // version then skips loading up to 2MB of project_json, not merely skips sending it.
        int version = publishedQueries.currentVersion(gameId);
        String eTag = GamePublishedQueryService.eTagOf(version);

        // Spring's own comparison: If-None-Match may carry a list and weak validators, and getting
        // that wrong silently returns 304 for a version the client does not have. It sets the status
        // and the ETag header itself, so the handler returns nothing.
        if (webRequest.checkNotModified(eTag)) {
            return null;
        }

        GamePublishedQueryService.PublishedView view = publishedQueries.find(gameId, viewerUserId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                // The snapshot's own tag, not the one computed above. The two reads are separate
                // transactions, so a publish landing between them would otherwise label v2 bytes with
                // a v1 validator — a response whose ETag contradicts its body, which a cache is
                // entitled to reuse for the wrong request.
                .eTag(view.eTag())
                .body(PublishedResponse.of(view));
    }

    /**
     * {@code {"expectedRevision": 7, "project": {…}}}.
     *
     * <p>Read by hand for the same reason the body is text: the project half must reach the validator
     * exactly as sent.
     */
    private record SaveRequest(int expectedRevision, String projectJson) {

        static SaveRequest read(String body) {
            com.fasterxml.jackson.databind.JsonNode root = GameProjectJson.parse(body);
            com.fasterxml.jackson.databind.JsonNode revision = root.get("expectedRevision");
            if (revision == null || !revision.isIntegralNumber() || revision.asInt() < 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "expectedRevision 이 필요합니다. 최초 저장은 0입니다.",
                        List.of(ApiErrorDetail.field("expectedRevision",
                                "0 이상의 정수여야 합니다.")), null);
            }
            com.fasterxml.jackson.databind.JsonNode project = root.get("project");
            if (project == null || !project.isObject()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "project 가 필요합니다.",
                        List.of(ApiErrorDetail.field("project", "GameProject 객체여야 합니다.")), null);
            }
            return new SaveRequest(revision.asInt(), GameProjectJson.write(project));
        }
    }

    private record CreateRequest() {

        static String readTitle(String body) {
            return GameProjectJson.textAt(GameProjectJson.parse(body), "title");
        }
    }

    private record VisibilityRequest() {

        static GameVisibility read(String body) {
            String raw = GameProjectJson.textAt(GameProjectJson.parse(body), "visibility");
            for (GameVisibility candidate : GameVisibility.values()) {
                if (candidate.name().equals(raw)) {
                    return candidate;
                }
            }
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "visibility 값이 올바르지 않습니다.",
                    List.of(ApiErrorDetail.field("visibility", "PRIVATE 또는 PUBLIC 이어야 합니다.")), null);
        }
    }

    public record MineResponse(List<GameLifecycleService.GameSummary> games) { }

    private record PublishRequest() {

        static int read(String body) {
            com.fasterxml.jackson.databind.JsonNode root = GameProjectJson.parse(body);
            com.fasterxml.jackson.databind.JsonNode revision = root.get("expectedRevision");
            if (revision == null || !revision.isIntegralNumber() || revision.asInt() < 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "expectedRevision 이 필요합니다.",
                        List.of(ApiErrorDetail.field("expectedRevision",
                                "0 이상의 정수여야 합니다.")), null);
            }
            return revision.asInt();
        }
    }

    /** {@code project} is embedded raw so the stored bytes reach the client unchanged. */
    public record DraftResponse(Long gameId, int revision, @JsonRawValue String project,
                                Instant updatedAt, List<String> warnings) {

        static DraftResponse of(GameDraftService.DraftView view) {
            return new DraftResponse(view.gameId(), view.revision(), view.projectJson(),
                    view.updatedAt(), List.of());
        }
    }

    public record PublishResponse(Long gameId, int publishedVersion, Instant publishedAt,
                                  List<String> warnings) { }

    public record PublishedResponse(String schemaVersion, Long gameId, int publishedVersion,
                                    @JsonRawValue String project, Instant publishedAt) {

        static PublishedResponse of(GamePublishedQueryService.PublishedView view) {
            return new PublishedResponse(view.schemaVersion(), view.gameId(), view.publishedVersion(),
                    view.projectJson(), view.publishedAt());
        }
    }
}
