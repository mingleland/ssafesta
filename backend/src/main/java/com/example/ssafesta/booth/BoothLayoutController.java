package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Booth Studio layout API (spec 005 contracts/layout-api.md §2~§5). */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/layouts")
public class BoothLayoutController {

    private final BoothLayoutService layouts;
    private final BoothLayoutQueryService queries;

    public BoothLayoutController(BoothLayoutService layouts, BoothLayoutQueryService queries) {
        this.layouts = layouts;
        this.queries = queries;
    }

    /** 204 when the booth has never been edited — the editor opens empty and first-saves with 0. */
    @GetMapping("/draft")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BoothLayoutQueryService.DraftView> draft(@AuthenticationPrincipal Jwt jwt,
                                                                   @PathVariable Long boothId) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        return queries.findDraft(boothId, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Saves the working copy.
     *
     * <p>Takes the body as text on purpose: Spring's ObjectMapper drops unknown fields silently, and
     * this API refuses them instead ({@link LayoutJson}).
     */
    @PutMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    public DraftSavedResponse saveDraft(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable Long boothId,
                                        @RequestBody String body) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        BoothLayoutService.SaveOutcome outcome = layouts.saveDraft(boothId, userId, body);
        return DraftSavedResponse.of(outcome);
    }

    @PostMapping("/publish")
    @SecurityRequirement(name = "bearerAuth")
    public PublishedResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable Long boothId) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        BoothLayoutService.PublishOutcome outcome = layouts.publish(boothId, userId);
        return new PublishedResponse(outcome.boothId(), outcome.publishedVersion(),
                outcome.publishedAt(), outcome.warnings());
    }

    /** Unauthenticated: this is what Unity and every visitor read on entering a booth (spec 006). */
    @GetMapping("/published")
    public BoothLayoutQueryService.PublishedView published(@PathVariable Long boothId) {
        return queries.findPublished(boothId);
    }

    /**
     * {@code warnings} is always present, empty when there is nothing to say — see
     * {@link com.example.ssafesta.common.ApiErrorResponse}.
     */
    public record DraftSavedResponse(Long boothId, long revision, Integer schemaVersion, String template,
                                     List<LayoutJson.LayoutObject> objects, Instant updatedAt,
                                     List<ApiErrorDetail> warnings) {

        static DraftSavedResponse of(BoothLayoutService.SaveOutcome outcome) {
            BoothLayoutDraft draft = outcome.draft();
            LayoutJson.LayoutDocument document = LayoutJson.parse(draft.getLayoutJson()).document();
            return new DraftSavedResponse(draft.getBoothId(), draft.getRevision(),
                    document.schemaVersion(), document.template(), document.objects(),
                    draft.getUpdatedAt(), outcome.warnings());
        }
    }

    public record PublishedResponse(Long boothId, int publishedVersion, Instant publishedAt,
                                    List<ApiErrorDetail> warnings) { }
}
