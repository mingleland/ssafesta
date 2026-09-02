package com.example.ssafesta.ai;

import java.util.Optional;

/**
 * What the document service needs from object storage, and nothing else (spec 007 FR-030).
 *
 * <p>Three methods rather than one because the row is written before the URL exists: the object key
 * embeds the document id, so the service needs the provider and bucket <i>before</i> the insert
 * (both columns are {@code NOT NULL}) and the signature only afterwards.
 *
 * <p>Which provider a new upload goes to is decided here, not by the caller — the service never
 * reads {@link AiStorageProperties}. Existing objects are the opposite: the caller passes the
 * provider and bucket recorded on the document row, because reads must follow the row and not the
 * currently active provider (data-model 저장소 Provider 불변식).
 *
 * <p>An interface with one implementation, which is normally a smell. It earns its place as a test
 * seam: {@link #headSize} is the only network call in the upload path, and there is no S3 container
 * in the test stack.
 */
interface AiDocumentStorage {

    /** Where a new upload goes. Read before the row is inserted — both fields are stored on it. */
    WriteTarget activeWriteTarget();

    /**
     * A presigned {@code PUT} for exactly this object, valid for the configured TTL (FR-026).
     *
     * <p>Signing is offline — no request leaves the process — so this is safe to call outside a
     * transaction and needs no test double of its own.
     */
    String presignPut(String provider, String bucket, String objectKey, String contentType,
                      long contentLength);

    /**
     * The stored object's size, or empty when there is no such object.
     *
     * <p>Empty means "storage answered, and it is not there". Anything that leaves the answer
     * unknown — an unreachable provider, one that is not configured — throws
     * {@link StorageUnavailableException} instead, because a missing object and an unanswerable
     * question lead to opposite responses (410 versus 503).
     */
    Optional<Long> headSize(String provider, String bucket, String objectKey);

    record WriteTarget(String provider, String bucket) {
    }
}
