package com.example.ssafesta.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One uploaded image's metadata (contract §8, V18).
 *
 * <p>Metadata only — the bytes live in object storage, and this row records <b>where</b>. The
 * {@code provider} and {@code storageBucket} written at issuance are the coordinates every later
 * read and delete uses, never the currently active write target: a MinIO fallback moves where new
 * uploads go and must not move where old ones are looked for (spec 007 FR-030, same invariant).
 */
@Entity
@Table(name = "game_assets")
public class GameAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false, updatable = false)
    private Long gameId;

    @Column(name = "asset_id", nullable = false, updatable = false, length = 64)
    private String assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private GameAssetKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GameAssetStatus status = GameAssetStatus.UPLOADING;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "sha256", length = 64)
    private String sha256;

    // A String, not an enum: the set of providers is defined by app.ai.storage.providers, and an
    // enum here would be a second list to keep in step with it for no gain.
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "storage_bucket", nullable = false)
    private String storageBucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "failure_rule", length = 60)
    private String failureRule;

    @Column(name = "declared_content_type", nullable = false, updatable = false, length = 100)
    private String declaredContentType;

    @Column(name = "declared_byte_size", nullable = false, updatable = false)
    private Long declaredByteSize;

    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected GameAsset() {
    }

    /**
     * Rows are inserted by {@link GameAssetRepository#insertIssued} rather than through this, because
     * a duplicate {@code assetId} has to be handled by {@code ON CONFLICT DO NOTHING} — catching the
     * constraint violation would leave the transaction unusable for the retry. The constructor stays
     * for tests that build a row directly.
     */
    GameAsset(Long gameId, String assetId, GameAssetKind kind, String declaredContentType,
              long declaredByteSize, String provider, String storageBucket, String objectKey,
              Instant uploadExpiresAt, Long createdByUserId) {
        this.gameId = gameId;
        this.assetId = assetId;
        this.kind = kind;
        this.status = GameAssetStatus.UPLOADING;
        this.provider = provider;
        this.storageBucket = storageBucket;
        this.objectKey = objectKey;
        this.declaredContentType = declaredContentType;
        this.declaredByteSize = declaredByteSize;
        this.uploadExpiresAt = uploadExpiresAt;
        this.createdByUserId = createdByUserId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Records what verification found and closes the grant.
     *
     * <p>Leaving {@code READY} is one-way. A second {@code PUT} against a presigned URL that has not
     * expired yet can still replace the object in storage, and nothing in Spring sees it — so the
     * grant's signature is issued for exactly {@code uploadExpiresAt}, and {@code /content} serves
     * only an object whose length still matches the {@code byteSize} recorded here (§3.4). A
     * replacement of exactly the same length is the one case that gets through.
     *
     * <p><b>{@code sha256} is written here and read by nothing.</b> It records which bytes passed
     * verification — enough to settle after the fact whether a stored object is the approved one,
     * and the upgrade path that would close the same-length hole. Checking it would mean hashing on
     * every read, and a play page pulls many assets, so that trade is not taken yet.
     */
    void markReady(GameAssetImageValidator.VerifiedImage verified) {
        this.contentType = verified.contentType();
        this.byteSize = verified.byteSize();
        this.width = verified.width();
        this.height = verified.height();
        this.sha256 = verified.sha256();
        this.status = GameAssetStatus.READY;
        this.failureRule = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Terminal. The rule is kept so the failure can be read back — a {@code FAILED} row with no
     * reason is the shape T-24 had, where the user saw nothing happen and no record said why.
     */
    void markFailed(String rule) {
        this.status = GameAssetStatus.FAILED;
        this.failureRule = rule;
        this.updatedAt = Instant.now();
    }

    boolean isDeleted() {
        return deletedAt != null;
    }

    boolean isGrantExpired(Instant now) {
        return !uploadExpiresAt.isAfter(now);
    }

    GameAssetState state() {
        if (deletedAt != null) {
            return GameAssetState.DELETED;
        }
        return switch (status) {
            case READY -> GameAssetState.READY;
            case UPLOADING -> GameAssetState.UPLOADING;
            case FAILED -> GameAssetState.FAILED;
        };
    }

    /**
     * The stored form of the reference (§2).
     *
     * <p>The server builds this string and the client never assembles it — the FE parses what comes
     * back and compares it to what it uploaded, so a format change here surfaces as a rejected
     * response instead of a Draft that saves and then shows nothing.
     */
    String source() {
        return "asset://game/" + gameId + "/" + assetId;
    }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public String getAssetId() { return assetId; }
    public GameAssetKind getKind() { return kind; }
    public GameAssetStatus getStatus() { return status; }
    public String getContentType() { return contentType; }
    public Long getByteSize() { return byteSize; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getSha256() { return sha256; }
    public String getProvider() { return provider; }
    public String getStorageBucket() { return storageBucket; }
    public String getObjectKey() { return objectKey; }
    public String getFailureRule() { return failureRule; }
    public String getDeclaredContentType() { return declaredContentType; }
    public Long getDeclaredByteSize() { return declaredByteSize; }
    public Instant getUploadExpiresAt() { return uploadExpiresAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
