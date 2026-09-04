package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Booth Layout")
public class BoothLayoutController {

    private final BoothLayoutService layouts;
    private final BoothLayoutQueryService queries;

    public BoothLayoutController(BoothLayoutService layouts, BoothLayoutQueryService queries) {
        this.layouts = layouts;
        this.queries = queries;
    }

    /** 204 when the booth has never been edited — the editor opens empty and first-saves with 0. */
    @Operation(summary = "작업본(Draft) 조회 — 편집기를 열 때 읽는다",
            description = """
                    아직 공개하지 않은 편집 중인 배치를 돌려준다. 소유자와 같은 부스 스태프만 볼 수 있다.

                    **한 번도 편집한 적이 없으면 `204 No Content` 이고 본문이 없다.** 오류가 아니다 —
                    편집기는 빈 캔버스로 열고 첫 저장에 `expectedRevision: 0` 을 보낸다.

                    응답의 `revision` 이 다음 저장에 그대로 필요하다. 그 값을 보내야 남의 저장을 덮어쓰지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업본과 현재 `revision`"),
            @ApiResponse(responseCode = "204", description = "편집 이력이 없다. 본문 없음 — 편집기는 빈 상태로 시작한다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_FORBIDDEN` — 내 부스도, 내가 스태프인 부스도 아니다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다")})
    @GetMapping("/draft")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BoothLayoutQueryService.DraftView> draft(@AuthenticationPrincipal Jwt jwt,
                                                                   @Parameter(description = "내 부스 식별자", example = "7")
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
    @Operation(summary = "작업본 저장 — 낙관적 잠금과 검증이 함께 돈다",
            description = """
                    편집 중인 배치를 저장한다. **저장은 공개가 아니다** — 방문자에게는 아직 아무 변화가 없다.

                    **`expectedRevision` 은 필수다.** 방금 읽은 작업본의 `revision` 을 그대로 넣고, 첫 저장이면 `0` 이다.
                    서버의 현재 값과 다르면 `409 LAYOUT_REVISION_CONFLICT` 로 거부하고 **아무것도 쓰지 않는다** —
                    같은 부스를 두 사람이 편집할 때 나중 저장이 앞 저장을 조용히 덮는 일을 막는다. 성공하면
                    `revision` 이 하나 올라간 값으로 돌아오므로 그것으로 다음 저장을 한다.

                    **모르는 필드는 무시하지 않고 거부한다.** 조용히 버리면 오타 난 필드가 저장된 것처럼 보이기 때문에,
                    이 endpoint 만 본문을 문자열로 받아 직접 파싱한다.

                    **`errors` 와 `warnings` 는 다르다**

                    - `errors` 가 비어 있지 않으면 저장이 **거부**된다 (`409 LAYOUT_VALIDATION_FAILED`).
                      오브젝트 12개 초과(`OBJECT_LIMIT`), 부스 밖 좌표, 중복 `objectId` 등이다.
                    - `warnings` 는 저장을 **막지 않는다.** 성공(200) 응답에도 붙는다 — 예: `CONFIG_NOT_LINKED`
                      (기능 오브젝트가 아직 아무것도 가리키지 않는다). 편집 중에는 정상 상태다.

                    두 배열은 항상 있고, 보고할 것이 없으면 빈 배열이다.

                    요청 본문의 모양은 spec 005 계약 문서(`specs/005-booth-studio-layout/contracts/layout-api.md` §3)가
                    소유한다 — `expectedRevision`·`schemaVersion`·`template`·`objects[]` 이며, 각 오브젝트는
                    `objectId`·`type`·`position`·`rotationY`·`configId` 를 가진다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공. 새 `revision` 과 `warnings` 가 함께 온다"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — JSON 이 아니거나 모르는 필드가 있거나 `expectedRevision` 이 없다"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_FORBIDDEN` — 편집 권한이 없다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 그런 부스가 없다"),
            @ApiResponse(responseCode = "409", description = """
                    `code` 로 세 가지를 가른다.

                    | `code` | 뜻 |
                    |---|---|
                    | `LAYOUT_REVISION_CONFLICT` | 다른 편집자가 먼저 저장했다. 작업본을 다시 읽고 재시도한다 |
                    | `LAYOUT_VALIDATION_FAILED` | 배치 규칙 위반. `errors[].rule` 과 `objectId` 가 어디가 문제인지 말한다 |
                    | `BOOTH_LEASE_EXPIRED` | 임대가 끝난 부스다 |
                    """)})
    @PutMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    public DraftSavedResponse saveDraft(@AuthenticationPrincipal Jwt jwt,
                                        @Parameter(description = "내 부스 식별자", example = "7")
                                        @PathVariable Long boothId,
                                        @RequestBody String body) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        BoothLayoutService.SaveOutcome outcome = layouts.saveDraft(boothId, userId, body);
        return DraftSavedResponse.of(outcome);
    }

    @Operation(summary = "공개(Publish) — 현재 작업본을 방문자에게 보이는 회차로 만든다",
            description = """
                    지금 저장된 작업본을 새 **공개 회차**로 굳힌다. 요청 본문이 없다 — 무엇을 공개할지는 서버가 가진
                    작업본이 정한다.

                    공개 후에도 작업본은 남는다. 계속 편집하다 다시 공개하면 회차가 또 하나 늘어난다. 방문자와 Unity 가
                    읽는 것은 **가장 최근 공개 회차**뿐이다.

                    공개 시점에 배치를 다시 검증한다. `errors` 가 있으면 공개되지 않고, `warnings` 는 공개를 막지 않되
                    응답에 함께 실린다 — "AI 직원이 연결되지 않은 채 공개됐다" 같은 사실을 소유자가 알아야 한다.

                    응답의 회차 필드 이름은 **`publishedVersion`** 이다. 부스 상세의 같은 개념 필드는
                    `publishedLayoutVersion` 이고, 두 이름을 합치지 않기로 확정했다 (#97).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공개 성공. 새 회차 번호·공개 시각·`warnings`"),
            @ApiResponse(responseCode = "403", description = "`BOOTH_FORBIDDEN` — 편집 권한이 없다"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND` — 부스가 없거나 공개할 작업본이 없다"),
            @ApiResponse(responseCode = "409", description = "`LAYOUT_VALIDATION_FAILED`(규칙 위반으로 공개 거부) 또는 `BOOTH_LEASE_EXPIRED`(임대 만료)")})
    @PostMapping("/publish")
    @SecurityRequirement(name = "bearerAuth")
    public PublishedResponse publish(@AuthenticationPrincipal Jwt jwt,
                                     @Parameter(description = "내 부스 식별자", example = "7")
                                     @PathVariable Long boothId) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        BoothLayoutService.PublishOutcome outcome = layouts.publish(boothId, userId);
        return new PublishedResponse(outcome.boothId(), outcome.publishedVersion(),
                outcome.publishedAt(), outcome.warnings());
    }

    /** Unauthenticated: this is what Unity and every visitor read on entering a booth (spec 006). */
    @Operation(summary = "공개 배치 조회 — 방문자와 Unity 가 읽는다",
            description = """
                    가장 최근 공개 회차의 배치를 돌려준다. **토큰이 없어도 호출된다** — 부스에 들어가는 모든 방문자가 읽는다.

                    작업본은 여기로 나오지 않는다. 편집 중인 내용은 공개하기 전까지 밖에서 보이지 않는다.

                    `version` 은 **공개 회차**, `schemaVersion` 은 **배치 JSON 구조 버전**이다. 서로 다른 값이며 같은
                    이름으로 부르면 Unity 가 하나로 파싱한다.

                    슬롯 기준으로 같은 값을 읽는 경로가 따로 있다 — `GET /api/v1/booth-slots/{slotId}/layouts/published`.
                    본문은 동일하다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공개된 배치"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`(부스가 없다) 또는 `LAYOUT_NOT_PUBLISHED`(공개한 배치가 아직 없다)"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED` — 임대가 끝나 공개본을 더 이상 제공하지 않는다")})
    @GetMapping("/published")
    public BoothLayoutQueryService.PublishedView published(
            @Parameter(description = "부스 식별자", example = "7") @PathVariable Long boothId) {
        return queries.findPublished(boothId);
    }

    /**
     * {@code warnings} is always present, empty when there is nothing to say — see
     * {@link com.example.ssafesta.common.ApiErrorResponse}.
     */
    public record DraftSavedResponse(
            @Schema(description = "저장한 부스", example = "7") Long boothId,
            @Schema(description = "저장 후 회차. **다음 저장의 `expectedRevision` 으로 이 값을 보낸다**", example = "1") long revision,
            @Schema(description = "배치 JSON 구조 버전. 공개 회차와 다른 개념이다", example = "1") Integer schemaVersion,
            @Schema(description = "적용된 템플릿 코드", example = "PROJECT_EXHIBITION") String template,
            @Schema(description = "저장된 오브젝트 목록") List<LayoutJson.LayoutObject> objects,
            @Schema(description = "저장 시각(UTC)", example = "2026-09-02T05:30:00Z") Instant updatedAt,
            @Schema(description = "저장을 막지 않은 경고. 없으면 빈 배열이며 키는 항상 있다") List<ApiErrorDetail> warnings) {

        static DraftSavedResponse of(BoothLayoutService.SaveOutcome outcome) {
            BoothLayoutDraft draft = outcome.draft();
            LayoutJson.LayoutDocument document = LayoutJson.parse(draft.getLayoutJson()).document();
            return new DraftSavedResponse(draft.getBoothId(), draft.getRevision(),
                    document.schemaVersion(), document.template(), document.objects(),
                    draft.getUpdatedAt(), outcome.warnings());
        }
    }

    public record PublishedResponse(
            @Schema(description = "공개한 부스", example = "7") Long boothId,
            @Schema(description = "새로 만들어진 공개 회차. 부스 상세에서는 `publishedLayoutVersion` 이라는 이름으로 같은 값을 본다",
                    example = "4") int publishedVersion,
            @Schema(description = "공개 시각(UTC)", example = "2026-09-02T05:31:00Z") Instant publishedAt,
            @Schema(description = "공개를 막지 않은 경고. 없으면 빈 배열이다") List<ApiErrorDetail> warnings) { }
}
