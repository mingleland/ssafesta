package com.example.ssafesta.ai;

import com.example.ssafesta.storage.ObjectStorage;
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

    /**
     * The statuses this class compares against. FastAPI never sees {@code EXPIRED} (data-model).
     *
     * <p>{@code FAILED} and {@code DISABLED} are the other two of FR-006 and are not here: the only
     * writes are SQL literals in {@code internal.ai}, which cannot see this class anyway, and a
     * constant nothing reads is a state that only looks reachable.
     */
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

    /**
     * The {@code READY} document this one was uploaded to replace (FR-019), or {@code null} for an
     * ordinary upload.
     *
     * <p>{@code updatable = false}: a row is a replacement or it is not, and nothing re-points one
     * at a different original. The retirement in {@code AiDocumentResultService.finalizeJob} reads
     * this to find which original to push out, so a later edit would retire the wrong document.
     */
    @Column(name = "replaces_document_id", updatable = false)
    private Long replacesDocumentId;

    /**
     * When this document was pushed out by its replacement (FR-027a). {@code null} on every other
     * row, including an {@code EXPIRED} one — that is the whole point: the same status means
     * "recoverable within 24 hours" without this stamp and "already superseded" with it.
     */
    @Column(name = "replaced_at")
    private Instant replacedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiDocument() {
    }

    AiDocument(Long boothId, Long agentId, Long uploadedByUserId, String originalFilename,
               String contentType, long sizeBytes, String contentSha256,
               ObjectStorage.WriteTarget target, Long replacesDocumentId, Instant now) {
        this.replacesDocumentId = replacesDocumentId;
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

    /** When the grant was issued — the clock the 1-hour expiry counts from, and the list's order. */
    Instant getCreatedAt() { return createdAt; }

    Instant getUploadedAt() { return uploadedAt; }

    Instant getExpiredAt() { return expiredAt; }

    Long getReplacesDocumentId() { return replacesDocumentId; }

    Instant getReplacedAt() { return replacedAt; }

    /** True while the grant is outstanding: the URL was issued and no upload has been verified. */
    boolean isAwaitingUpload() {
        return QUEUED.equals(processingStatus) && uploadedAt == null;
    }

    boolean isExpired() {
        return EXPIRED.equals(processingStatus);
    }

    /** The only status a 수정본 교체 may target (FR-019) — see {@code AiDocumentService.replace}. */
    boolean isReady() {
        return READY.equals(processingStatus);
    }

    /** Called once, in the transaction that inserted the row — the key needs the generated id. */
    void assignObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    void markUploaded(Instant now) {
        this.uploadedAt = now;
        this.updatedAt = now;
    }

    /**
     * The grant went unused, its provider is no longer the one we write to (FR-032), or it was an
     * unfinished 교체 attempt the owner replaced with a different file.
     *
     * <p><b>{@code replacedAt} stays null here, deliberately.</b> All three cases are abandoned
     * uploads, not documents that something newer took over — stamping them would tell
     * {@code /complete} that a late completion has already been superseded when in fact nothing
     * ever went live. Only {@code AiDocumentJobRepository.retireReplacedOriginal} writes that
     * column, and only for an original a replacement actually reached {@code READY} over.
     */
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
