package com.example.ssafesta.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * What the document service needs from object storage, and nothing else (spec 007 FR-030).
 *
 * <p>Three methods rather than one because the row is written before the URL exists: the object key
 * embeds the document id, so the service needs the provider and bucket <i>before</i> the insert
 * (both columns are {@code NOT NULL}) and the signature only afterwards.
 *
 * <p>Which provider a new upload goes to is decided here, not by the caller — the service never
 * reads {@link ObjectStorageProperties}. Existing objects are the opposite: the caller passes the
 * provider and bucket recorded on the document row, because reads must follow the row and not the
 * currently active provider (data-model 저장소 Provider 불변식).
 *
 * <p>An interface with one implementation, which is normally a smell. It earns its place as a test
 * seam: {@link #headSize} is the only network call in the upload path, and there is no S3 container
 * in the test stack.
 */
public interface ObjectStorage {

    /** Where a new upload goes. Read before the row is inserted — both fields are stored on it. */
    WriteTarget activeWriteTarget();

    /**
     * A presigned {@code PUT} for exactly this object, valid for {@code ttl}.
     *
     * <p>The lifetime is the caller's, not the configuration's: 007 grants 15 minutes (FR-026) and
     * 019 grants 10 (game-asset-upload.md §3.1). Signing for longer than the row's own gate would
     * let bytes land after the row has already been refused, leaving an object nothing points at.
     *
     * <p>Signing is offline — no request leaves the process — so this is safe to call outside a
     * transaction and needs no test double of its own.
     */
    String presignPut(String provider, String bucket, String objectKey, String contentType,
                      long contentLength, Duration ttl);

    /**
     * The stored object's size, or empty when there is no such object.
     *
     * <p>Empty means "storage answered, and it is not there". Anything that leaves the answer
     * unknown — an unreachable provider, one that is not configured — throws
     * {@link StorageUnavailableException} instead, because a missing object and an unanswerable
     * question lead to opposite responses (410 versus 503).
     */
    Optional<Long> headSize(String provider, String bucket, String objectKey);

    /**
     * The stored bytes, or empty when there is no such object — same distinction as
     * {@link #headSize}: absent answers, unanswerable throws.
     *
     * <p>Bounded on purpose. 019 verifies magic bytes, pixel dimensions and SHA-256 in Spring
     * (game-asset-upload.md §5), so the bytes have to come back here — unlike 007, which hands that
     * job to FastAPI. A caller that trusted the declared size would let a lying uploader stream
     * until the heap gave out, so the limit is passed in.
     *
     * <p>Reads one byte <b>past</b> the limit and returns it. Stopping exactly at the limit cannot
     * tell an object that is exactly {@code maxBytes} from a larger one, and truncating silently
     * would hand the caller bytes that are not the object. Deciding what an over-long read means is
     * the caller's — for 019 the image validator already refuses it with the right rule
     * ({@code SIZE_EXCEEDED}), which an exception thrown from here would have replaced with a
     * worse one.
     *
     * @param maxBytes read at most this many bytes plus one; never truncate silently
     */
    Optional<byte[]> getObject(String provider, String bucket, String objectKey, long maxBytes);

    /**
     * Removes the object. <b>Idempotent</b> — a key that is already gone is a success.
     *
     * <p>That is what lets the withdrawal delete queue retry safely: the sweeper cannot tell "I
     * deleted it last run and died before clearing the queue row" from "it was never there"
     * (game-asset-upload.md §7.1, infra-002 삭제 규칙 4).
     */
    void deleteObject(String provider, String bucket, String objectKey);

    record WriteTarget(String provider, String bucket) {
    }
}
