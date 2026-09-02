package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public GrantResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long gameId,
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
    @PostMapping("/{assetId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    public GameAssetService.AssetView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long gameId,
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
    @GetMapping("/{assetId}/content")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable Long gameId,
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
