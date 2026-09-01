package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator image upload (spec 019 contracts/game-asset-upload.md).
 *
 * <p>Three of the contract's six endpoints — the three the FE actually calls
 * ({@code remoteAssetRepository.ts}). Listing, single-status polling and delete belong to the editor
 * UI that is not built yet, so they are not here.
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
     * <p>{@code uploadUrl} is absolute-path relative, which is what the FE needs — it calls
     * {@code fetch(uploadUrl, ...)} straight from the browser and its API base already resolves
     * against this host.
     */
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public GrantResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long gameId,
                              @RequestBody StartRequest request) {
        Long userId = GamePrincipal.requireMemberId(jwt);
        GameAssetKind kind = request.readKind();
        GameAssetService.IssuedGrant grant = assets.issue(gameId, userId, kind,
                request.contentType(), request.byteSize() == null ? 0L : request.byteSize());
        return new GrantResponse(grant.assetId(), GameAssetStatus.UPLOADING.name(),
                uploadUrl(gameId, grant.assetId(), grant.rawToken()),
                Map.of(HttpHeaders.CONTENT_TYPE, request.contentType()),
                grant.expiresAt());
    }

    /**
     * Receives the bytes (contract §3.1's PUT step, pointed at this application).
     *
     * <p>No bearer token: the FE's upload call sends none, because the contract described this step
     * as a request to an object store. {@code t} is the credential and it is checked against the
     * row's stored hash.
     *
     * <p>The body is read through a bounded stream rather than bound as a parameter. Letting the
     * framework materialise it first would mean a caller can decide how much memory to allocate here
     * — the whole point of a size limit is that it applies before that.
     */
    @PutMapping("/{assetId}/upload")
    public ResponseEntity<Void> upload(@PathVariable Long gameId, @PathVariable String assetId,
                                       @RequestParam("t") String token, HttpServletRequest request) {
        assets.storeUpload(gameId, assetId, token, readBounded(request));
        return ResponseEntity.noContent().build();
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
     * <p>Served here rather than redirected: the FE fetches this with an {@code Authorization} header
     * and reads the body as a blob, and a 302 into a bucket would need browser GET CORS that
     * infra-002 does not grant.
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

    private String uploadUrl(Long gameId, String assetId, String token) {
        return "/api/v1/games/" + gameId + "/assets/" + assetId + "/upload?t=" + token;
    }

    /**
     * Reads at most one byte past the limit, then refuses.
     *
     * <p>Reading the extra byte is how "exactly at the limit" stays acceptable while anything larger
     * is refused without having been stored.
     */
    private byte[] readBounded(HttpServletRequest request) {
        long limit = GameAssetImageValidator.MAX_BYTES;
        try (InputStream input = request.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ApiException(ErrorCode.GAME_ASSET_TOO_LARGE,
                            "이미지는 " + (limit / 1024 / 1024) + "MB 이하만 올릴 수 있습니다.",
                            List.of(ApiErrorDetail.of("SIZE_EXCEEDED",
                                    "이미지는 " + (limit / 1024 / 1024) + "MB 이하만 올릴 수 있습니다.")),
                            null);
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.GAME_ASSET_CORRUPTED, "업로드가 중단되었습니다.",
                    List.of(ApiErrorDetail.of("UPLOAD_INTERRUPTED", "업로드가 중단되었습니다.")), null);
        }
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
