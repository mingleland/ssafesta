package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator image upload (spec 019 contracts/game-asset-upload.md).
 *
 * <p>Three of the contract's six endpoints — the three the FE actually calls
 * ({@code remoteAssetRepository.ts}). Listing, single-status polling and delete belong to the editor
 * UI that is not built yet, so they are not here.
 *
 * <p>There is no PUT endpoint. The bytes go from the browser to the bucket with a presigned URL, so
 * this application never receives an upload body and never needs an unauthenticated route to accept
 * one — which is also what removes the upload token from a query string (#69, 2026-08-28).
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/assets")
@Tag(name = "Game Asset")
public class GameAssetController {

    private final GameAssetService assets;

    public GameAssetController(GameAssetService assets) {
        this.assets = assets;
    }

    /**
     * Starts an upload: issues the {@code assetId} and the grant (contract §3.1).
     *
     * <p>{@code uploadUrl} is an absolute presigned URL into the bucket. The FE calls
     * {@code fetch(uploadUrl, {method: 'PUT', headers: requiredHeaders, body: file})}, so it must
     * send exactly the headers named here — the content type is part of the signature.
     */
    @Operation(summary = "이미지 업로드 시작 — presigned URL 을 받는다",
            description = """
                    게임에 쓸 이미지를 올리기 위한 **일회용 업로드 주소**를 받는다. 바이트는 이 서버를 거치지 않고
                    브라우저에서 저장소로 직접 간다.

                    ### 세 단계다

                    1. 이 endpoint 로 `assetId` 와 `uploadUrl` 을 받는다
                    2. `uploadUrl` 에 **`PUT`** 으로 파일을 올린다 — `requiredHeaders` 를 **그대로** 실어야 한다.
                       `Content-Type` 이 서명에 포함되므로 다른 값을 보내면 저장소가 거절하고 **그 실패는 이 서버 로그에 남지 않는다**
                    3. `POST /complete` 로 검증을 받는다

                    `uploadUrl` 의 유효 시간은 **10분**(`expiresAt`)이고 서명 수명도 같다.

                    ### 여기서 보내는 `contentType`·`byteSize` 는 거절용이다

                    통과는 `complete` 시점의 **실제 바이트 검사**가 결정한다. `1 KiB` 라고 선언하고 큰 파일을 올리는 경우가
                    바로 선언값이 못 잡는 경우이므로, 최종 상한은 실제로 읽은 바이트에 걸린다.

                    `uploadUrl` 은 민감정보다 — 로그·저장소·캐시에 남기지 말고 GameProject 에는 어떤 경우에도 저장하지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`assetId`·`uploadUrl`·`requiredHeaders`·`expiresAt`. `status` 는 `UPLOADING` 이다"),
            @ApiResponse(responseCode = "400", description = "`GAME_ASSET_KIND_UNSUPPORTED` — `IMAGE`·`TILESET` 만 올릴 수 있다. 다른 값은 기본값으로 넘기지 않고 거절한다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `GAME_FORBIDDEN`(내 게임이 아니다)"),
            @ApiResponse(responseCode = "404", description = "`GAME_NOT_FOUND` 또는 `GAME_DELETED` — 없거나 삭제된 게임이다"),
            @ApiResponse(responseCode = "409", description = "`GAME_ASSET_QUOTA_EXCEEDED` — 게임 하나에 올릴 수 있는 이미지 수를 넘었다"),
            @ApiResponse(responseCode = "413", description = "`GAME_ASSET_TOO_LARGE` — 선언한 크기가 상한을 넘었다"),
            @ApiResponse(responseCode = "415", description = "`GAME_ASSET_TYPE_UNSUPPORTED` — 허용 목록 밖의 형식이다 (SVG 포함)")})
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public GrantResponse start(@AuthenticationPrincipal Jwt jwt,
                               @Parameter(description = "내 게임 식별자", example = "123")
                               @PathVariable Long gameId,
                               @RequestBody StartRequest request) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        GameAssetKind kind = request.readKind();
        GameAssetService.IssuedGrant grant = assets.issue(gameId, userId, kind,
                request.contentType(), request.byteSize() == null ? 0L : request.byteSize());
        return new GrantResponse(grant.assetId(), GameAssetStatus.UPLOADING.name(), grant.uploadUrl(),
                Map.of(HttpHeaders.CONTENT_TYPE, grant.requiredContentType()),
                grant.expiresAt());
    }

    /**
     * Verifies what arrived and closes the asset out (contract §3.2).
     *
     * <p>Idempotent, and a verification failure still answers {@code 200} — with {@code status}
     * {@code FAILED} and the rule that refused it. The FE treats anything other than {@code READY}
     * as an error, and answering with a body means the failure is recorded rather than rolled back.
     */
    @Operation(summary = "업로드 완료 — 올라간 파일을 검증하고 확정한다",
            description = """
                    `uploadUrl` 에 파일을 올린 뒤 부른다. **검증은 전부 여기서 한다** — 서버가 업로드 본문을 보지 못하므로
                    저장소에서 객체를 다시 읽어 확인한다.

                    확인하는 것은 실제 바이트다: 파일 형식(magic number), 디코드 가능 여부, 실제 크기, 픽셀 치수.
                    시작 단계에서 선언한 값이 아니다.

                    ### 실패도 `200` 이다

                    검증에 실패하면 `status` 가 **`FAILED`** 이고 거절 이유가 `errors[].rule` 에 담긴다. 오류 응답이 아닌 이유는
                    그 실패가 **기록되어야** 하기 때문이다 — 되돌리면 무엇이 왜 거절됐는지 남지 않는다.
                    **`READY` 가 아닌 모든 응답을 실패로 다루면 된다.**

                    `FAILED` 는 되살리지 않는다. 재시도는 업로드 시작부터 **새 `assetId`** 로 한다 — 실패한 객체를 덮어쓰게 두면
                    "검증을 통과한 바이트" 와 "저장된 바이트" 가 갈릴 수 있다.

                    ### 여러 번 불러도 안전하다

                    같은 `assetId` 로 다시 부르면 앞의 결과를 그대로 돌려주고 검증을 다시 돌리지 않는다.

                    성공하면 `source`(`asset://game/{gameId}/{assetId}`)가 나온다. **작업본 저장과 게시에서 쓰는 값이 이것이다.**
                    `uploadUrl` 이나 `/content` 주소를 저장하지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`status` 가 `READY` 면 `source`·`contentType`·`byteSize`·`width`·`height` 가 채워진다. `FAILED` 면 `errors[].rule` 에 거절 이유가 있다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `GAME_FORBIDDEN`(내 게임이 아니다)"),
            @ApiResponse(responseCode = "404", description = "`GAME_ASSET_NOT_FOUND`(그런 `assetId` 가 없다) 또는 `GAME_NOT_FOUND`·`GAME_DELETED`"),
            @ApiResponse(responseCode = "409", description = "`GAME_ASSET_DELETED` — 삭제된 자산이다")})
    @PostMapping("/{assetId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    public GameAssetService.AssetView complete(@AuthenticationPrincipal Jwt jwt,
                                               @Parameter(description = "내 게임 식별자", example = "123")
                                               @PathVariable Long gameId,
                                               @Parameter(description = "업로드 시작에서 받은 자산 식별자", example = "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc")
                                               @PathVariable String assetId) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        return assets.complete(gameId, assetId, userId);
    }

    /**
     * Delivers the image (contract §3.4).
     *
     * <p>Proxied rather than redirected: the FE fetches this with an {@code Authorization} header
     * and reads the body as a blob, and a 302 into the bucket would need browser GET CORS that
     * object-storage-contract.md does not grant.
     *
     * <p>{@code Content-Type} comes from verification, never from what the uploader declared, and
     * {@code nosniff} stops the browser from second-guessing it — the pair is what keeps a disguised
     * file from being interpreted as something else.
     */
    @Operation(summary = "이미지 내려받기 — 서버가 바이트를 중계한다",
            description = """
                    올려서 확정된 이미지의 **바이트를 그대로** 응답한다. 저장소로 redirect 하지 않고 임시 주소도 내주지 않는다 —
                    그런 주소는 자신을 만들어 낸 권한 검사보다 오래 살아서, 비공개 게임의 이미지가 검사 없이 열린 채로 남는다.

                    `source` 값 `asset://game/{gameId}/{assetId}` 를 이 경로로 바꿔 부르면 된다.

                    ### 토큰이 없어도 `200` 이다 (그래서 자물쇠가 없다)

                    - **소유자**는 언제나 받는다
                    - **소유자가 아닌 사람**(게스트 포함)은 그 게임이 `PUBLIC` 이고 게시본이 있으며 **게시본이 이 이미지를 실제로 참조할 때만** 받는다.
                      "공개 게임이면 그 이미지도 공개" 가 아니다 — 그러면 게시되지 않은 작업본에만 있는 이미지가 열린다

                    응답은 `Cache-Control: private, max-age=300` 과 `X-Content-Type-Options: nosniff` 이고,
                    `Content-Type` 은 **검증으로 확정된 실제 타입**이다 (올린 사람이 선언한 값이 아니다).

                    ### 확정 당시의 길이와 정확히 같을 것을 요구한다

                    길이가 다르면 우리가 승인한 객체가 아니므로 `GAME_ASSET_NOT_READY` + rule `OBJECT_MISSING` 으로 거절한다 —
                    객체가 사라진 경우와 같은 응답이고, 둘 다 "행은 확정인데 저장소가 그것을 뒷받침하지 않는다" 다.
                    이 응답을 받으면 다시 올려야 한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 바이트. `Content-Type` 은 검증으로 확정된 타입이다"),
            @ApiResponse(responseCode = "403", description = "`GAME_ASSET_FORBIDDEN` — 비공개·미게시 게임이거나 게시본이 이 이미지를 참조하지 않는다"),
            @ApiResponse(responseCode = "404", description = "`GAME_ASSET_NOT_FOUND` 또는 `GAME_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`GAME_ASSET_NOT_READY` — 확정 전이거나(`UPLOADING`·`FAILED`), 저장소가 확정된 바이트를 더 이상 뒷받침하지 않는다(rule `OBJECT_MISSING`)")})
    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt,
                                          @Parameter(description = "게임 식별자", example = "123")
                                          @PathVariable Long gameId,
                                          @Parameter(description = "자산 식별자", example = "aB7kQ2mZ9xR4tL6vN0wY3sJ8pc")
                                          @PathVariable String assetId) {
        GameAssetService.AssetContent asset =
                assets.content(gameId, assetId, GamePrincipal.optionalMemberId(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.content().length)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
                .body(asset.content());
    }

    /**
     * @param kind        {@code IMAGE} or {@code TILESET}; anything else is refused rather than
     *                    defaulted, so an {@code AUDIO} attempt is told it is unsupported
     * @param contentType declared, used only to refuse early (§3.1)
     * @param byteSize    declared, same
     * @param fileName    carried by the FE request; not stored, and never used to decide type
     */
    public record StartRequest(String kind, String contentType, Long byteSize, String fileName) {

        GameAssetKind readKind() {
            for (GameAssetKind candidate : GameAssetKind.values()) {
                if (candidate.name().equals(kind)) {
                    return candidate;
                }
            }
            String message = "지원하지 않는 자산 종류입니다. 이미지만 올릴 수 있습니다.";
            throw new ApiException(ErrorCode.GAME_ASSET_KIND_UNSUPPORTED, message,
                    List.of(ApiErrorDetail.of("KIND_UNSUPPORTED", message)), null);
        }
    }

    /** Field names are the FE's — {@code parseStartGrant} reads exactly these (§3.1). */
    public record GrantResponse(String assetId, String status, String uploadUrl,
                                Map<String, String> requiredHeaders, Instant expiresAt) { }
}
