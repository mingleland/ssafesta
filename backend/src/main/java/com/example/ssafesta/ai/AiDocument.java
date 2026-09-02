package com.example.ssafesta.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One uploaded original for an AI agent (spec 007 US2, data-model "AI Document — Spring 소유").
 *
 * <p>The row is created when the upload URL is granted, not when the bytes arrive: the object key
 * embeds the document id, so the id has to exist first. {@link #uploadedAt} is what separates the
 * two — {@code null} means a grant was handed out and nothing has confirmed the file landed.
 *
 * <p>{@link #objectKey} maps to the V1 column {@code s3_key}. The name predates the decision to
 * support more than one S3-compatible provider (C-07); renaming the column would touch a table the
 * AI part also reads, and the field name here is the one that matters to Java.
 */
@Entity
@Table(name = "ai_documents")
public class AiDocument {

    /** Spring-side statuses. FastAPI never sees {@code EXPIRED} (data-model). */
    static final String QUEUED = "QUEUED";
    static final String PROCESSING = "PROCESSING";
    static final String READY = "READY";
    static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Null only between the insert and {@link #assignObjectKey} — see the class note. */
    @Column(name = "s3_key", length = 1024)
    private String objectKey;

    /**
     * What the client claimed the bytes hash to. A pre-check, not proof (FR-019a): FastAPI hashes
     * the stored original and fails the job on a mismatch.
     */
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    /**
     * Where this object lives — fixed when the grant was issued.
     *
     * <p>Reads, HEADs and deletes follow this and {@link #storageBucket}, <b>never</b> the currently
     * active write provider (FR-030). A document written before a fallback switch is still in the
     * old provider, and asking the new one about it answers "not there" about the wrong bucket.
     */
    @Column(name = "storage_provider", nullable = false, length = 20)
    private String storageProvider;

    @Column(name = "storage_bucket", nullable = false, length = 255)
    private String storageBucket;

    @Column(name = "processing_status", nullable = false, length = 30)
    private String processingStatus = QUEUED;

    @Column(name = "uploaded_by_user_id", nullable = false, updatable = false)
    private Long uploadedByUserId;

    /** When the upload was verified against storage. {@code null} = granted, unconfirmed. */
    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    /** When the row went {@code EXPIRED}. The 24-hour recovery window is measured from here. */
    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiDocument() {
    }

    AiDocument(Long boothId, Long agentId, Long uploadedByUserId, String originalFilename,
               String contentType, long sizeBytes, String contentSha256,
               AiDocumentStorage.WriteTarget target, Instant now) {
        this.boothId = boothId;
        this.agentId = agentId;
        this.uploadedByUserId = uploadedByUserId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.contentSha256 = contentSha256;
        this.storageProvider = target.provider();
        this.storageBucket = target.bucket();
        this.processingStatus = QUEUED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    Long getId() { return id; }

    Long getBoothId() { return boothId; }

    Long getAgentId() { return agentId; }

    String getOriginalFilename() { return originalFilename; }

    String getContentType() { return contentType; }

    long getSizeBytes() { return sizeBytes; }

    String getObjectKey() { return objectKey; }

    String getContentSha256() { return contentSha256; }

    String getStorageProvider() { return storageProvider; }

    String getStorageBucket() { return storageBucket; }

    String getProcessingStatus() { return processingStatus; }

    Instant getUploadedAt() { return uploadedAt; }

    Instant getExpiredAt() { return expiredAt; }

    /** True while the grant is outstanding: the URL was issued and no upload has been verified. */
    boolean isAwaitingUpload() {
        return QUEUED.equals(processingStatus) && uploadedAt == null;
    }

    boolean isExpired() {
        return EXPIRED.equals(processingStatus);
    }

    /** Called once, in the transaction that inserted the row — the key needs the generated id. */
    void assignObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    void markUploaded(Instant now) {
        this.uploadedAt = now;
        this.updatedAt = now;
    }

    /** The grant went unused, or its provider is no longer the one we write to (FR-032). */
    void expire(Instant now) {
        this.processingStatus = EXPIRED;
        this.expiredAt = now;
        this.updatedAt = now;
    }

    /** A late completion inside the 24-hour window (FR-027) — same document, back in the queue. */
    void recover(Instant now) {
        this.processingStatus = QUEUED;
        this.expiredAt = null;
        this.uploadedAt = now;
        this.updatedAt = now;
    }
}
