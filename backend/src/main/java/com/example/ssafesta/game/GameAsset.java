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
 * One uploaded image's metadata (contract §8, V16).
 *
 * <p><b>The bytes are deliberately not a field here.</b> The column exists, but a 5 MiB {@code
 * byte[]} on the entity means every {@code findById} — the quota scan, the validator snapshot, the
 * status poll — drags the image through the heap. Hibernate's lazy basic mapping only works with
 * bytecode enhancement, so declaring it lazy would read as a fix while still loading eagerly.
 * {@link GameAssetRepository} reads and writes {@code content} with its own statements, which also
 * makes every place that touches image bytes visible in one file.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private GameAssetProvider provider = GameAssetProvider.DB;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "failure_rule", length = 60)
    private String failureRule;

    @Column(name = "declared_content_type", nullable = false, updatable = false, length = 100)
    private String declaredContentType;

    @Column(name = "declared_byte_size", nullable = false, updatable = false)
    private Long declaredByteSize;

    @Column(name = "upload_token_hash", nullable = false, length = 64)
    private String uploadTokenHash;

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
              long declaredByteSize, String uploadTokenHash, Instant uploadExpiresAt, Long createdByUserId) {
        this.gameId = gameId;
        this.assetId = assetId;
        this.kind = kind;
        this.status = GameAssetStatus.UPLOADING;
        this.provider = GameAssetProvider.DB;
        this.declaredContentType = declaredContentType;
        this.declaredByteSize = declaredByteSize;
        this.uploadTokenHash = uploadTokenHash;
        this.uploadExpiresAt = uploadExpiresAt;
        this.createdByUserId = createdByUserId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Records what verification found and closes the grant.
     *
     * <p>Clearing {@code uploadTokenHash} is what stops a second {@code PUT} to the same URL from
     * replacing bytes that already passed (§3.2's warning, which the contract only wrote down for
     * {@code FAILED}). The status check in the upload handler covers the same ground; both are here
     * because either one alone is a single line away from being removed by accident.
     */
    void markReady(GameAssetImageValidator.VerifiedImage verified) {
        this.contentType = verified.contentType();
        this.byteSize = verified.byteSize();
        this.width = verified.width();
        this.height = verified.height();
        this.sha256 = verified.sha256();
        this.status = GameAssetStatus.READY;
        this.failureRule = null;
        this.uploadTokenHash = CONSUMED_TOKEN_HASH;
        this.updatedAt = Instant.now();
    }

    /**
     * Terminal. The rule is kept so the failure can be read back — a {@code FAILED} row with no
     * reason is the shape T-24 had, where the user saw nothing happen and no record said why.
     */
    void markFailed(String rule) {
        this.status = GameAssetStatus.FAILED;
        this.failureRule = rule;
        this.uploadTokenHash = CONSUMED_TOKEN_HASH;
        this.updatedAt = Instant.now();
    }

    /**
     * A used-up token still has to satisfy {@code upload_token_hash NOT NULL}, and it must never
     * match a real token. Sixty-four zeroes cannot be produced by hashing, so comparisons against it
     * fail without a null branch at every call site.
     */
    static final String CONSUMED_TOKEN_HASH = "0".repeat(64);

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
    public GameAssetProvider getProvider() { return provider; }
    public String getObjectKey() { return objectKey; }
    public String getFailureRule() { return failureRule; }
    public String getDeclaredContentType() { return declaredContentType; }
    public Long getDeclaredByteSize() { return declaredByteSize; }
    String getUploadTokenHash() { return uploadTokenHash; }
    public Instant getUploadExpiresAt() { return uploadExpiresAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
