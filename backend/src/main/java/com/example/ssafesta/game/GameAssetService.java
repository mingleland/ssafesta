package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading, verifying and serving a creator's own images (contract game-asset-upload.md).
 *
 * <p>The three-step shape — issue a grant, {@code PUT} the bytes, {@code complete} — is the FE's
 * committed contract and is kept exactly. The only departure is where {@code uploadUrl} points:
 * at this application rather than at an object store. The FE performs a plain {@code fetch} against
 * whatever URL it is handed, so that substitution is invisible to it, and it removes presigned URLs,
 * bucket CORS and immutable-key promotion from a path whose only consumer is the web runtime.
 */
@Service
public class GameAssetService {

    /** Contract §3.3. Returned in the list response so the number can change without an FE deploy. */
    static final int MAX_ASSETS_PER_GAME = 300;

    /** Contract §3.1. */
    static final Duration GRANT_TTL = Duration.ofMinutes(10);

    /**
     * How long a row that can never become usable is kept.
     *
     * <p>A {@code FAILED} row has no diagnostic value once the user has retried, and an expired
     * {@code UPLOADING} row has none at all. Keeping them forever would grow the table without bound
     * while the delete endpoint is still unbuilt.
     */
    static final Duration UNUSABLE_RETENTION = Duration.ofHours(1);

    /** Contract §2 — 추측 불가능한 26자, and it must satisfy the FE's {@code STABLE_ID} pattern. */
    private static final int ASSET_ID_LENGTH = 26;

    private static final String ID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String ID_FIRST_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** A collision at 26 random characters is vanishing, but "vanishing" is not "handled". */
    private static final int ID_ATTEMPTS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameAssetRepository assets;
    private final GameRepository games;
    private final GamePublishedVersionRepository publishedVersions;
    private final GameAccessGuard guard;
    private final GameAssetImageValidator imageValidator;

    public GameAssetService(GameAssetRepository assets, GameRepository games,
                            GamePublishedVersionRepository publishedVersions, GameAccessGuard guard,
                            GameAssetImageValidator imageValidator) {
        this.assets = assets;
        this.games = games;
        this.publishedVersions = publishedVersions;
        this.guard = guard;
        this.imageValidator = imageValidator;
    }

    /**
     * Issues the {@code assetId} and the one-shot upload grant (contract §3.1).
     *
     * <p>Everything happens under a lock on the {@code games} row: the unusable-row cleanup, the
     * quota count and the insert. Counting outside the lock is the classic version of this bug —
     * two requests both read 299 and both insert.
     *
     * <p>The declared content type and size are used to <b>refuse</b> early and never to accept.
     * What passes is decided by {@link #complete} from the bytes that actually arrived.
     */
    @Transactional
    public IssuedGrant issue(Long gameId, Long userId, GameAssetKind kind,
                             String declaredContentType, long declaredByteSize) {
        guard.requireOwnedLive(gameId, userId);
        games.findByIdForUpdate(gameId).orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));

        if (declaredContentType == null
                || !GameAssetImageValidator.ALLOWED_CONTENT_TYPES.contains(declaredContentType)) {
            throw refuse(ErrorCode.GAME_ASSET_TYPE_UNSUPPORTED, "MIME_NOT_ALLOWED",
                    "PNG · JPEG · GIF · WebP 만 올릴 수 있습니다.");
        }
        if (declaredByteSize <= 0 || declaredByteSize > GameAssetImageValidator.MAX_BYTES) {
            throw refuse(ErrorCode.GAME_ASSET_TOO_LARGE, "SIZE_EXCEEDED",
                    "이미지는 " + (GameAssetImageValidator.MAX_BYTES / 1024 / 1024) + "MB 이하만 올릴 수 있습니다.");
        }

        Instant now = Instant.now();
        assets.deleteUnusable(gameId, now.minus(UNUSABLE_RETENTION));
        if (assets.countChargeable(gameId, now) >= MAX_ASSETS_PER_GAME) {
            throw refuse(ErrorCode.GAME_ASSET_QUOTA_EXCEEDED, "QUOTA_EXCEEDED",
                    "이 게임에는 이미지를 " + MAX_ASSETS_PER_GAME + "개까지 올릴 수 있습니다. "
                            + "쓰지 않는 이미지를 정리한 뒤 다시 시도해 주세요.");
        }

        String rawToken = randomToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = now.plus(GRANT_TTL);

        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            String assetId = randomAssetId();
            if (assets.insertIssued(gameId, assetId, kind.name(), declaredContentType, declaredByteSize,
                    tokenHash, expiresAt, userId) == 1) {
                return new IssuedGrant(assetId, expiresAt, rawToken);
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "자산 식별자를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * Takes the bytes the client {@code PUT} against the grant URL.
     *
     * <p>The token is the only credential here — the FE's upload call carries no {@code
     * Authorization}, because the contract wrote that step as a request to somebody else's server.
     * So the grant is verified by the statement itself: the update matches only a row that is still
     * {@code UPLOADING}, unexpired, and holding this exact token hash.
     *
     * <p>A miss is reported without probing the row. Any of "no such asset", "already finished",
     * "expired", "wrong token" would otherwise answer a question the caller has not authenticated
     * itself to ask, and the FE turns every failure here into the same retry prompt regardless.
     */
    @Transactional
    public void storeUpload(Long gameId, String assetId, String rawToken, byte[] content) {
        if (content == null || content.length == 0) {
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "EMPTY_CONTENT", "이미지 파일이 비어 있습니다.");
        }
        if (rawToken == null || rawToken.isBlank()) {
            throw refuse(ErrorCode.GAME_ASSET_FORBIDDEN, "UPLOAD_TOKEN_INVALID",
                    "업로드 주소가 유효하지 않습니다. 처음부터 다시 올려 주세요.");
        }
        if (assets.storeContent(gameId, assetId, sha256Hex(rawToken), content) != 1) {
            throw refuse(ErrorCode.GAME_ASSET_FORBIDDEN, "UPLOAD_TOKEN_INVALID",
                    "업로드 주소가 만료되었거나 이미 사용되었습니다. 처음부터 다시 올려 주세요.");
        }
    }

    /**
     * Verifies the uploaded bytes and closes the asset out (contract §3.2).
     *
     * <p>The row is locked first, so two concurrent calls cannot both run verification: the second
     * waits and then returns the first one's result. That is what makes this idempotent rather than
     * usually-idempotent.
     *
     * <p><b>A verification failure returns {@code FAILED}; it does not throw.</b> Throwing would roll
     * the transaction back and lose the record of the failure, leaving the row {@code UPLOADING} to
     * be retried forever. The contract's own response shape carries {@code status} for this reason,
     * and the FE already refuses anything that is not {@code READY}.
     */
    @Transactional
    public AssetView complete(Long gameId, String assetId, Long userId) {
        guard.requireOwnedLive(gameId, userId);
        GameAsset asset = assets.findForUpdate(gameId, assetId)
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_ASSET_NOT_FOUND));
        if (asset.isDeleted()) {
            throw new ApiException(ErrorCode.GAME_ASSET_DELETED);
        }
        if (asset.getStatus() != GameAssetStatus.UPLOADING) {
            return AssetView.of(asset);
        }

        if (asset.isGrantExpired(Instant.now())) {
            return fail(asset, "GRANT_EXPIRED");
        }
        byte[] content = assets.readContent(gameId, assetId);
        if (content == null || content.length == 0) {
            return fail(asset, "UPLOAD_MISSING");
        }

        GameAssetImageValidator.VerifiedImage verified;
        try {
            verified = imageValidator.verify(content, asset.getDeclaredByteSize());
        } catch (ApiException refusal) {
            return fail(asset, ruleOf(refusal));
        }
        asset.markReady(verified);
        return AssetView.of(asset);
    }

    private AssetView fail(GameAsset asset, String rule) {
        asset.markFailed(rule);
        assets.clearContent(asset.getGameId(), asset.getAssetId());
        return AssetView.of(asset);
    }

    /**
     * Serves the bytes (contract §3.4, delivered by this application rather than by a redirect).
     *
     * <p>Being served here and not from storage is what lets the FE keep using an authenticated
     * {@code fetch}: a 302 into a bucket needs browser GET CORS on that bucket, and infra-002 grants
     * {@code PUT} only. There is no redirect to get wrong.
     *
     * @param userId the caller, or {@code null} for a guest
     */
    @Transactional(readOnly = true)
    public AssetContent content(Long gameId, String assetId, Long userId) {
        GameAsset asset = assets.findByGameIdAndAssetId(gameId, assetId)
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_ASSET_NOT_FOUND));
        if (asset.getStatus() != GameAssetStatus.READY) {
            throw new ApiException(ErrorCode.GAME_ASSET_NOT_READY);
        }
        Game game = games.findById(gameId).orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));
        if (!game.isOwnedBy(userId)) {
            requirePublishedReference(game, asset);
        }
        byte[] content = assets.readContent(gameId, assetId);
        if (content == null) {
            // ck_game_assets_ready_has_bytes makes this unreachable; if it ever fires, the data is
            // wrong and saying so beats returning an empty image.
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "자산 데이터가 손상되었습니다.");
        }
        return new AssetContent(asset.getContentType(), content);
    }

    /**
     * What a non-owner has to satisfy — all four, not just "the game is public".
     *
     * <p>Visibility belongs to the game and an asset belongs to a published revision, so "PUBLIC
     * game ⇒ its assets are public" would expose an image that exists only in an unpublished Draft.
     * A {@code PUBLIC} game's unreferenced {@code READY} asset is refused for the same reason.
     *
     * <p>A deleted asset still passes when the published revision references it: Published is
     * immutable, and a version whose pictures disappear is not immutable (§7).
     */
    private void requirePublishedReference(Game game, GameAsset asset) {
        if (game.isDeleted() || game.getVisibility() != GameVisibility.PUBLIC
                || game.getPublishedVersion() == null) {
            throw new ApiException(ErrorCode.GAME_ASSET_FORBIDDEN);
        }
        GamePublishedVersion version = publishedVersions
                .findByGameIdAndVersionNo(game.getId(), game.getPublishedVersion())
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_ASSET_FORBIDDEN));
        if (!referencesSource(version.getProjectJson(), asset.source())) {
            throw new ApiException(ErrorCode.GAME_ASSET_FORBIDDEN);
        }
    }

    private boolean referencesSource(String projectJson, String source) {
        JsonNode project = GameProjectJson.parse(projectJson);
        for (JsonNode declared : GameProjectJson.arrayAt(project, "assets")) {
            if (source.equals(GameProjectJson.textAt(declared, "source"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The asset states this game currently has, for Draft and Publish validation (decision C).
     *
     * <p>States rather than a set of usable ids, because §6 distinguishes missing, not-ready, deleted
     * and foreign, and the editor shows different text for each.
     */
    @Transactional(readOnly = true)
    public Map<String, GameAssetState> stateSnapshot(Long gameId) {
        List<GameAsset> rows = assets.findAllByGameId(gameId);
        Map<String, GameAssetState> snapshot = new HashMap<>(rows.size());
        for (GameAsset asset : rows) {
            snapshot.put(asset.getAssetId(), asset.state());
        }
        return Map.copyOf(snapshot);
    }

    private String ruleOf(ApiException refusal) {
        List<ApiErrorDetail> errors = refusal.errors();
        if (errors == null || errors.isEmpty() || errors.get(0).rule() == null) {
            return "VALIDATION_FAILED";
        }
        return errors.get(0).rule();
    }

    /**
     * First character is a letter, because the FE's {@code STABLE_ID} pattern requires it
     * ({@code ^[A-Za-z][A-Za-z0-9_-]{0,63}$}) and would reject an id starting with a digit.
     */
    private String randomAssetId() {
        StringBuilder id = new StringBuilder(ASSET_ID_LENGTH);
        id.append(ID_FIRST_ALPHABET.charAt(RANDOM.nextInt(ID_FIRST_ALPHABET.length())));
        while (id.length() < ASSET_ID_LENGTH) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", exception);
        }
    }

    private ApiException refuse(ErrorCode code, String rule, String message) {
        return new ApiException(code, message, List.of(ApiErrorDetail.of(rule, message)), null);
    }

    /** {@code rawToken} never leaves the issuing response — it is not stored and not logged (§3.1). */
    public record IssuedGrant(String assetId, Instant expiresAt, String rawToken) { }

    public record AssetContent(String contentType, byte[] content) { }

    public record AssetView(String assetId, GameAssetStatus status, GameAssetKind kind, String source,
                            String contentType, Long byteSize, Integer width, Integer height,
                            String failureRule) {

        static AssetView of(GameAsset asset) {
            return new AssetView(asset.getAssetId(), asset.getStatus(), asset.getKind(), asset.source(),
                    asset.getContentType(), asset.getByteSize(), asset.getWidth(), asset.getHeight(),
                    asset.getFailureRule());
        }
    }
}
