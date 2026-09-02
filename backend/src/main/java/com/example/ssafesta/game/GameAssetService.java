package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.storage.ObjectStorage;
import com.example.ssafesta.storage.StorageUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading, verifying and serving a creator's own images (contract game-asset-upload.md).
 *
 * <p>The three-step shape — issue a grant, {@code PUT} the bytes, {@code complete} — is the FE's
 * committed contract. {@code uploadUrl} is a presigned {@code PUT} straight into the bucket, so the
 * bytes never pass through this process on the way up; {@code GET /content} is the opposite and is
 * proxied from here. That asymmetry is not a preference — object-storage-contract.md grants the
 * bucket {@code AllowedMethods: PUT} only, so a browser GET against it (or a 302 into it) dies in
 * CORS, while the FE reads assets with an authenticated {@code fetch} (remoteAssetRepository.ts).
 *
 * <p>Nothing here trusts what the browser says it uploaded. The presigned URL pins the content type
 * and length the quota check approved, and {@code complete} reads the object back and verifies it
 * (§5) — a signature is permission to write one object, not a statement about its contents.
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
    private final ObjectStorage storage;
    private final GameAssetDeleteQueue deleteQueue;

    public GameAssetService(GameAssetRepository assets, GameRepository games,
                            GamePublishedVersionRepository publishedVersions, GameAccessGuard guard,
                            GameAssetImageValidator imageValidator, ObjectStorage storage,
                            GameAssetDeleteQueue deleteQueue) {
        this.assets = assets;
        this.games = games;
        this.publishedVersions = publishedVersions;
        this.guard = guard;
        this.imageValidator = imageValidator;
        this.storage = storage;
        this.deleteQueue = deleteQueue;
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
        Instant staleBefore = now.minus(UNUSABLE_RETENTION);
        // Queue first, delete second, same predicate: after the delete there is nothing left to read
        // the coordinates from.
        assets.enqueueUnusableObjects(gameId, staleBefore);
        assets.deleteUnusable(gameId, staleBefore);
        if (assets.countChargeable(gameId, now) >= MAX_ASSETS_PER_GAME) {
            throw refuse(ErrorCode.GAME_ASSET_QUOTA_EXCEEDED, "QUOTA_EXCEEDED",
                    "이 게임에는 이미지를 " + MAX_ASSETS_PER_GAME + "개까지 올릴 수 있습니다. "
                            + "쓰지 않는 이미지를 정리한 뒤 다시 시도해 주세요.");
        }

        // Read once and store on the row. Reading it again at upload time would follow a fallback
        // that moved in between, and the object would be signed for a bucket the row does not name.
        ObjectStorage.WriteTarget target = storage.activeWriteTarget();
        Instant expiresAt = now.plus(GRANT_TTL);

        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            String assetId = randomAssetId();
            String objectKey = objectKey(gameId, assetId);
            if (assets.insertIssued(gameId, assetId, kind.name(), target.provider(), target.bucket(),
                    objectKey, declaredContentType, declaredByteSize, expiresAt, userId) == 1) {
                // Signed for exactly the grant's lifetime — a longer signature would let bytes land
                // after complete has already refused the row, leaving an object nothing points at.
                String uploadUrl = storage.presignPut(target.provider(), target.bucket(), objectKey,
                        declaredContentType, declaredByteSize, GRANT_TTL);
                return new IssuedGrant(assetId, expiresAt, uploadUrl, declaredContentType);
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "자산 식별자를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
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
        // Bounded by the contract's own limit, not by the declared size: a client that declared
        // 1 KiB and uploaded 4 GiB is exactly the case a declared bound would not catch. An object
        // over the limit arrives one byte too long and the validator refuses it below with
        // SIZE_EXCEEDED — the same rule a too-large declared size gets.
        byte[] content = readObject(asset, GameAssetImageValidator.MAX_BYTES);
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

    /**
     * Marks the row {@code FAILED} and hands the object to the delete queue.
     *
     * <p>Not a direct {@code deleteObject}: this runs inside the transaction that writes {@code
     * FAILED}, and a storage call there either rolls the failure record back or leaves a committed
     * row whose delete silently did not happen. The queue row is written by the same commit, so the
     * two facts cannot disagree.
     */
    private AssetView fail(GameAsset asset, String rule) {
        asset.markFailed(rule);
        deleteQueue.enqueue(asset.getProvider(), asset.getStorageBucket(), asset.getObjectKey());
        return AssetView.of(asset);
    }

    /**
     * Reads the object this row names, or {@code null} when storage says it is not there.
     *
     * <p>Comes back with at most {@code maxBytes + 1} bytes and no judgement attached. A provider
     * that cannot answer throws {@link StorageUnavailableException} and it is left to propagate:
     * 503, not {@code FAILED}, because a row marked terminally failed by an outage cannot be
     * retried and its object is queued for deletion.
     */
    private byte[] readObject(GameAsset asset, long maxBytes) {
        return storage.getObject(asset.getProvider(), asset.getStorageBucket(),
                asset.getObjectKey(), maxBytes).orElse(null);
    }

    /**
     * Serves the bytes (contract §3.4, delivered by this application rather than by a redirect).
     *
     * <p>Proxied, not redirected. A 302 into the bucket would need browser GET CORS there and
     * object-storage-contract.md grants {@code PUT} only, so the FE's authenticated {@code fetch}
     * would die on the redirect — and a presigned GET handed to the browser would be a URL that
     * outlives the permission check that produced it.
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
        // The verified size, not the contract limit: this row passed verification at that size, so
        // a different length now is not the object that was approved. The read is one byte longer
        // than the recorded size for exactly this comparison.
        byte[] content = readObject(asset, asset.getByteSize());
        if (content == null || content.length != asset.getByteSize()) {
            // The row says READY but storage does not back that up — the object is gone, or it is no
            // longer the one that was verified. §6 says to answer with GAME_ASSET_NOT_READY and a
            // rule rather than invent a code, and GAME_ASSET_DELETED would be a claim about a
            // deletion that never happened.
            throw refuse(ErrorCode.GAME_ASSET_NOT_READY, "OBJECT_MISSING",
                    "이미지를 불러올 수 없습니다. 다시 올려 주세요.");
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

    /**
     * Where the object lives (contract §8).
     *
     * <p>Contains the game id and the server-issued asset id and nothing the uploader chose — a
     * filename in the key would carry a path, a traversal or another tenant's name into the bucket.
     * One asset id is used once, so the key never has to be reused or versioned.
     */
    private static String objectKey(Long gameId, String assetId) {
        return "games/" + gameId + "/assets/" + assetId;
    }

    private ApiException refuse(ErrorCode code, String rule, String message) {
        return new ApiException(code, message, List.of(ApiErrorDetail.of(rule, message)), null);
    }

    /**
     * @param requiredContentType the {@code Content-Type} the browser's {@code PUT} must send. It is
     *     signed into {@code uploadUrl}, so any other value fails the signature — the FE has to be
     *     told which one, or every upload 403s with nothing to read.
     */
    public record IssuedGrant(String assetId, Instant expiresAt, String uploadUrl,
                              String requiredContentType) { }

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
