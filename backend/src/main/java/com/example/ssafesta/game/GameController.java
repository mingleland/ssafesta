package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Game Studio")
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
    @Operation(summary = "게임 만들기 — 빈 게임 슬롯을 하나 얻는다",
            description = """
                    제목만 받아 게임을 만든다. 회원 전용이며 게스트는 게임을 만들 수 없다 (FR-023).

                    **작업본은 만들어지지 않는다.** 편집기가 자기 시작 프로젝트를 갖고 있고, 첫 저장
                    (`PUT /api/v1/games/{gameId}/draft`, `expectedRevision: 0`)에서 그것이 서버로 넘어온다.
                    그래서 만든 직후 작업본 조회는 `204` 다.

                    응답의 `Location` 헤더에 새 게임의 경로가 들어온다.

                    **만들 수 있는 개수에 상한이 있다.** 살아 있는 게임 수가 상한이면 `409 GAME_LIMIT_EXCEEDED` 이고,
                    현재 상한 값은 응답 `message` 에 들어 있다. 삭제본 보관 상한은 이것과 **별개 예산**이라
                    라이브러리가 꽉 찼다고 삭제가 막히지는 않는다.

                    요청 본문: `{"title": "…"}`
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공. `Location` 헤더와 요약 정보"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `title` 이 없거나 규칙 위반이다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 게임을 만들 수 없다"),
            @ApiResponse(responseCode = "409", description = "`GAME_LIMIT_EXCEEDED` — 살아 있는 게임 수 상한에 도달했다. 상한 값은 `message` 에 있다")})
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
    @Operation(summary = "내 게임 목록 — 삭제한 것까지 포함",
            description = """
                    내가 만든 게임을 전부 돌려준다. **소프트 삭제한 게임도 포함**된다 — 복원(`POST /{gameId}/restore`)
                    화면이 그 목록을 보여줘야 하기 때문이다. 각 항목의 상태로 살아 있는 것과 삭제된 것을 가른다.

                    공개된 남의 게임을 찾는 목록이 아니다. 그것은 페이지네이션과 검색이 필요한 별개 기능이며 v1 범위가 아니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "내 게임 목록"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다")})
    @GetMapping("/mine")
    @SecurityRequirement(name = "bearerAuth")
    public MineResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return new MineResponse(lifecycle.listOwned(GamePrincipal.requireMemberId(jwt)));
    }

    @Operation(summary = "공개 설정 변경 — PRIVATE ↔ PUBLIC",
            description = """
                    게임을 남이 플레이할 수 있게 하거나 다시 숨긴다. 요청 본문은 `{"visibility": "PUBLIC"}` 이다.

                    **공개 설정과 게시는 별개의 축이다.** `PUBLIC` 으로 바꿨어도 게시(`POST /{gameId}/publish`)한 것이
                    없으면 `GET /{gameId}/published` 는 `404 GAME_NOT_PUBLISHED` 다. 반대로 게시본이 있는 게임을
                    `PRIVATE` 로 되돌리면 같은 조회가 `403 GAME_NOT_PUBLIC` 이 된다 — 게시본은 그대로 남아 있고
                    다시 `PUBLIC` 으로 바꾸면 즉시 보인다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경된 요약 정보"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `visibility` 가 `PRIVATE`·`PUBLIC` 이 아니다"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다) 또는 `GAME_DELETED`(삭제된 게임이다)")})
    @PatchMapping("/{gameId}")
    @SecurityRequirement(name = "bearerAuth")
    public GameLifecycleService.GameSummary changeVisibility(@AuthenticationPrincipal Jwt jwt,
                                                            @Parameter(description = "내 게임 식별자", example = "42")
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
    @Operation(summary = "게임 삭제 — 소프트 삭제이며 복원할 수 있다",
            description = """
                    게임을 삭제 상태로 옮긴다. 데이터는 남아 있고 `POST /api/v1/games/{gameId}/restore` 로 되돌릴 수 있다.
                    삭제된 게임은 `GET /api/v1/games/mine` 에 계속 나오고, 게시본 조회는 더 이상 제공되지 않는다.

                    **두 번 호출하면 `404 GAME_DELETED` 다.** 실패가 아니라 "이미 삭제됨"으로 읽으라는 것이 계약이다 —
                    응답을 못 받아 재시도한 클라이언트가 정확히 이 분기로 들어온다.

                    삭제본 보관에도 상한이 있어, 보관 칸이 꽉 차면 삭제가 `409 GAME_LIMIT_EXCEEDED` 로 거부될 수 있다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 완료. 본문 없음"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다) 또는 `GAME_DELETED`(이미 삭제됨 — 재시도의 정상 응답으로 취급한다)"),
            @ApiResponse(responseCode = "409", description = "`GAME_LIMIT_EXCEEDED` — 삭제본 보관 상한이 꽉 찼다")})
    @DeleteMapping("/{gameId}")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                       @Parameter(description = "내 게임 식별자", example = "42")
                                       @PathVariable Long gameId) {
        lifecycle.softDelete(gameId, GamePrincipal.requireMemberId(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Restore. Takes a live slot, so it passes the same cap creation does. */
    @Operation(summary = "삭제한 게임 복원",
            description = """
                    소프트 삭제한 게임을 다시 살린다. 요청 본문이 없다.

                    복원은 **살아 있는 칸을 하나 차지**하므로 생성과 같은 상한을 통과해야 한다 — 라이브러리가 꽉 차 있으면
                    `409 GAME_LIMIT_EXCEEDED` 이고, 다른 게임을 지우거나 정리한 뒤 다시 시도한다.

                    작업본과 게시본은 삭제 시점 그대로 돌아온다. 공개 설정도 그대로다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "복원된 요약 정보"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND` — 그런 게임이 없다"),
            @ApiResponse(responseCode = "409", description = "`GAME_LIMIT_EXCEEDED` — 살아 있는 게임 상한이 꽉 차 복원할 자리가 없다")})
    @PostMapping("/{gameId}/restore")
    @SecurityRequirement(name = "bearerAuth")
    public GameLifecycleService.GameSummary restore(@AuthenticationPrincipal Jwt jwt,
                                                   @Parameter(description = "삭제 상태인 내 게임 식별자", example = "42")
                                                   @PathVariable Long gameId) {
        return lifecycle.restore(gameId, GamePrincipal.requireMemberId(jwt));
    }

    /**
     * {@code 204} when the game has never been saved — the editor keeps its starter project.
     *
     * <p>Not {@code 404}: that is reserved for "no such game", and collapsing the two would make the
     * editor open in an error state on every new game (#104 ①).
     */
    @Operation(summary = "게임 작업본 조회 — 편집기를 열 때 읽는다",
            description = """
                    편집 중인 게임 프로젝트(JSON)를 그대로 돌려준다. 소유자만 볼 수 있다.

                    **한 번도 저장하지 않았으면 `204 No Content` 다.** `404` 가 아닌 이유는 그 자리가 "그런 게임 없음"
                    이기 때문이고, 둘을 합치면 새 게임을 만들 때마다 편집기가 오류 화면으로 열린다 (#104 ①).
                    `204` 를 받은 편집기는 자기 시작 프로젝트를 그대로 쓴다.

                    응답의 `revision` 이 다음 저장의 `expectedRevision` 이다. `project` 는 **저장된 바이트 그대로**
                    실려 온다 — 서버가 다시 직렬화하지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업본과 현재 `revision`"),
            @ApiResponse(responseCode = "204", description = "아직 저장한 적이 없다. 본문 없음 — 편집기는 시작 프로젝트로 연다"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다) 또는 `GAME_DELETED`(삭제된 게임이다)")})
    @GetMapping("/{gameId}/draft")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DraftResponse> draft(@AuthenticationPrincipal Jwt jwt,
                                               @Parameter(description = "내 게임 식별자", example = "42")
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
    @Operation(summary = "게임 작업본 저장 — 게시와 무관하게 편집 내용만 보관한다",
            description = """
                    편집 중인 프로젝트를 저장한다. **저장은 게시가 아니다** — 플레이하는 사람에게는 아무 변화가 없다.

                    요청 본문은 두 부분이다.

                    ```json
                    { "expectedRevision": 7, "project": { … GameProject … } }
                    ```

                    **`expectedRevision` 은 필수이고 첫 저장은 `0`** 이다. 서버의 현재 값과 다르면
                    `409 GAME_REVISION_CONFLICT` 로 거부하고 아무것도 쓰지 않는다. 이때 `errors[]` 의
                    `CURRENT_REVISION` 이 서버의 현재 회차를 **십진수 문자열**로 담고 있어, 클라이언트가 문장을
                    파싱하지 않고 그 값으로 재동기화할 수 있다.

                    **모르는 필드는 거부한다** (`MALFORMED_PROJECT`). 조용히 버리면 오타 난 필드가 저장된 것처럼
                    보이므로, 이 endpoint 는 본문을 문자열로 받아 계약 스키마로 직접 검증한다. `project` 는 검증기에
                    **보낸 바이트 그대로** 전달된다.

                    검증 실패는 `409 GAME_VALIDATION_FAILED` 이고 `errors[].rule` 이 어떤 규칙을 어겼는지 말한다.
                    규칙 목록은 `specs/019-game-studio/contracts/game-api.md` 가 소유한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공. 새 `revision` 과 저장된 `project`"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `expectedRevision` 이 없거나 음수, `project` 가 객체가 아니다"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다) 또는 `GAME_DELETED`(삭제된 게임이다)"),
            @ApiResponse(responseCode = "409", description = """
                    | `code` | 뜻 |
                    |---|---|
                    | `GAME_REVISION_CONFLICT` | 다른 편집 내용이 먼저 저장됐다. `errors[]` 의 `CURRENT_REVISION` 이 서버 회차를 십진수 문자열로 담는다 |
                    | `GAME_VALIDATION_FAILED` | 프로젝트 규칙 위반. `errors[].rule` 로 어디가 문제인지 안다 |
                    | `GAME_SCHEMA_UNSUPPORTED` | 지원하지 않는 `schemaVersion` 이다 |
                    """)})
    @PutMapping(value = "/{gameId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    public DraftResponse saveDraft(@AuthenticationPrincipal Jwt jwt,
                                   @Parameter(description = "내 게임 식별자", example = "42")
                                   @PathVariable Long gameId,
                                   @RequestBody String body) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        SaveRequest request = SaveRequest.read(body);
        return DraftResponse.of(
                draftService.save(gameId, userId, request.expectedRevision(), request.projectJson()));
    }

    @Operation(summary = "게시(Publish) — 지금 작업본을 플레이 가능한 회차로 굳힌다",
            description = """
                    작업본을 새 **게시 회차**로 복사한다. 요청 본문은 `{"expectedRevision": 7}` 하나이며,
                    **게시하려는 작업본의 회차를 확인하는 값**이다 — 다르면 `409 GAME_REVISION_CONFLICT` 로 거부한다.
                    편집기가 화면에 들고 있는 것과 다른 내용이 게시되는 일을 막는다.

                    게시 후에도 작업본은 그대로 남아 계속 편집할 수 있다. 플레이되는 것은 **가장 최근 게시 회차**뿐이고,
                    다시 게시하면 회차가 하나 늘어난다.

                    게시하려면 프로젝트가 완성 규칙을 통과해야 한다. 실패는 `409 GAME_VALIDATION_FAILED` 이고
                    `warnings` 는 게시를 막지 않되 응답에 함께 실린다.

                    **공개 설정과는 별개다.** 게시했더라도 게임이 `PRIVATE` 이면 남은 플레이할 수 없다
                    (`GET /{gameId}/published` 가 `403 GAME_NOT_PUBLIC`).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시 성공. 새 회차·게시 시각·`warnings`"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `expectedRevision` 이 없거나 음수다"),
            @ApiResponse(responseCode = "403", description = "`GAME_FORBIDDEN` — 내 게임이 아니다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다), `GAME_DELETED`(삭제됨), 또는 게시할 작업본이 없다"),
            @ApiResponse(responseCode = "409", description = "`GAME_REVISION_CONFLICT`(작업본 회차 불일치), `GAME_VALIDATION_FAILED`(완성 규칙 위반), `GAME_SCHEMA_UNSUPPORTED`")})
    @PostMapping("/{gameId}/publish")
    @SecurityRequirement(name = "bearerAuth")
    public PublishResponse publish(@AuthenticationPrincipal Jwt jwt,
                                   @Parameter(description = "내 게임 식별자", example = "42")
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
    @Operation(summary = "게시본 조회 — 플레이할 프로젝트를 받아 간다",
            description = """
                    가장 최근 게시 회차의 프로젝트를 돌려준다. **토큰이 없어도 호출된다** — 게스트도 게시된 게임을
                    플레이할 수 있다 (FR-023). 게임을 만들거나 저장하는 것만 회원 전용이다.

                    **캐시 규칙** — 이 URL 은 "현재 게시본"을 따라가므로 `Cache-Control: no-cache` 다. 다시 게시하면
                    즉시 보여야 한다. 대신 `ETag` 를 준다: 다음 호출에 `If-None-Match` 로 그 값을 보내면 회차가
                    그대로일 때 `304` 로 답하고 **최대 2MB 짜리 본문을 다시 읽지도, 보내지도 않는다.**
                    회차를 고정한 URL 에 긴 `max-age` 를 주는 방식은 나중 과제다.

                    게시본이 없거나 비공개면 본문 대신 오류가 온다 — 아래 코드로 구분한다. 특히 `403 GAME_NOT_PUBLIC` 은
                    "게시는 됐지만 지금 비공개"라는 뜻이고, `404 GAME_NOT_PUBLISHED` 는 "공개 설정과 무관하게 아직
                    게시본이 없다"는 뜻이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시본 프로젝트. `ETag` 헤더가 함께 온다"),
            @ApiResponse(responseCode = "304", description = "`If-None-Match` 가 현재 회차와 같다. 본문 없음 — 갖고 있는 것을 그대로 쓴다"),
            @ApiResponse(responseCode = "403", description = "`GAME_NOT_PUBLIC` — 게시본은 있지만 현재 비공개다"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND`(없다), `GAME_DELETED`(삭제됨), `GAME_NOT_PUBLISHED`(아직 게시된 회차가 없다)")})
    @GetMapping("/{gameId}/published")
    public ResponseEntity<PublishedResponse> published(@AuthenticationPrincipal Jwt jwt,
                                                       @Parameter(description = "게임 식별자", example = "42")
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

    public record MineResponse(
            @Schema(description = "내가 만든 게임 전체. 소프트 삭제된 것도 포함한다") List<GameLifecycleService.GameSummary> games) { }

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
    public record DraftResponse(
            @Schema(description = "게임 식별자", example = "42") Long gameId,
            @Schema(description = "저장 후 회차. **다음 저장·게시의 `expectedRevision` 이 이 값이다**", example = "8") int revision,
            @Schema(description = "GameProject JSON. 저장된 바이트가 그대로 실린다 — 서버가 재직렬화하지 않는다",
                    type = "object") @JsonRawValue String project,
            @Schema(description = "저장 시각(UTC)", example = "2026-09-02T05:40:00Z") Instant updatedAt,
            @Schema(description = "저장을 막지 않은 경고. 없으면 빈 배열이다") List<String> warnings) {

        static DraftResponse of(GameDraftService.DraftView view) {
            return new DraftResponse(view.gameId(), view.revision(), view.projectJson(),
                    view.updatedAt(), List.of());
        }
    }

    public record PublishResponse(
            @Schema(description = "게임 식별자", example = "42") Long gameId,
            @Schema(description = "새로 만들어진 게시 회차", example = "3") int publishedVersion,
            @Schema(description = "게시 시각(UTC)", example = "2026-09-02T05:41:00Z") Instant publishedAt,
            @Schema(description = "게시를 막지 않은 경고. 없으면 빈 배열이다") List<String> warnings) { }

    public record PublishedResponse(
            @Schema(description = "GameProject 구조 버전. 게시 회차와 다른 개념이다", example = "1.0") String schemaVersion,
            @Schema(description = "게임 식별자", example = "42") Long gameId,
            @Schema(description = "이 응답이 담은 게시 회차", example = "3") int publishedVersion,
            @Schema(description = "플레이할 GameProject JSON. 게시 시점 바이트 그대로다", type = "object")
            @JsonRawValue String project,
            @Schema(description = "게시 시각(UTC)", example = "2026-09-02T05:41:00Z") Instant publishedAt) {

        static PublishedResponse of(GamePublishedQueryService.PublishedView view) {
            return new PublishedResponse(view.schemaVersion(), view.gameId(), view.publishedVersion(),
                    view.projectJson(), view.publishedAt());
        }
    }
}
