package com.example.ssafesta.storage;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Object storage that lives in a map.
 *
 * <p>There is no S3 container in the test stack and adding one to assert "we called HEAD" would buy
 * nothing — the interesting behaviour is what the service does with the answer. {@link #putObject}
 * stands in for the browser's PUT.
 *
 * <p>Objects are keyed by <b>bucket and key together</b>, not by key alone. That is what makes a
 * lookup against the wrong bucket miss, which is the whole point of the reconcile race: a HEAD sent
 * before the object moved must not answer for where it moved to.
 *
 * <p>Sizes and bytes are stored separately on purpose. 007 only ever asks for the size, so its
 * tests keep declaring a size and no payload — a 5 MiB {@code putObject} must not allocate 5 MiB.
 * 019 needs the bytes themselves (it validates magic numbers and dimensions in Spring), so those
 * tests use {@link #putBytes}, which fills both.
 *
 * <p>{@link #onHead} is the seam for the races: it runs while the service is between its read and
 * its write, which is exactly where the expiry sweeper and reconcile land in production. That makes
 * those tests deterministic instead of timing-dependent.
 */
public class FakeObjectStorage implements ObjectStorage {

    private final Map<String, Long> objects = new ConcurrentHashMap<>();
    private final Map<String, byte[]> contents = new ConcurrentHashMap<>();

    private volatile String provider = "R2";
    private volatile String bucket = "test-ai-documents";
    private volatile Consumer<String> onHead = key -> { };
    private volatile RuntimeException headFailure;
    private volatile RuntimeException deleteFailure;

    @Override
    public WriteTarget activeWriteTarget() {
        return new WriteTarget(provider, bucket);
    }

    @Override
    public String presignPut(String provider, String bucket, String objectKey, String contentType,
                             long contentLength, Duration ttl) {
        // Shaped like a real presigned URL so a test can tell one issue from the next.
        return "https://fake.storage.test/" + bucket + "/" + objectKey
                + "?sig=" + System.nanoTime() + "&len=" + contentLength
                + "&ttl=" + ttl.toSeconds();
    }

    @Override
    public Optional<Long> headSize(String provider, String bucket, String objectKey) {
        onHead.accept(objectKey);
        if (headFailure != null) {
            throw headFailure;
        }
        if (!provider.equals(this.provider)) {
            // A row pointing at a provider this deployment has no client for — unknown, not absent.
            throw new StorageUnavailableException("설정되지 않은 저장소입니다.");
        }
        return Optional.ofNullable(objects.get(slot(bucket, objectKey)));
    }

    @Override
    public Optional<byte[]> getObject(String provider, String bucket, String objectKey, long maxBytes) {
        if (!provider.equals(this.provider)) {
            throw new StorageUnavailableException("설정되지 않은 저장소입니다.");
        }
        byte[] content = contents.get(slot(bucket, objectKey));
        if (content == null) {
            return Optional.empty();
        }
        // Same bound as the real one: at most maxBytes + 1, so the caller can tell "at the limit"
        // from "over" without this deciding anything.
        return Optional.of(content.length > maxBytes
                ? Arrays.copyOf(content, (int) maxBytes + 1)
                : content);
    }

    @Override
    public void deleteObject(String provider, String bucket, String objectKey) {
        if (deleteFailure != null) {
            throw deleteFailure;
        }
        objects.remove(slot(bucket, objectKey));
        contents.remove(slot(bucket, objectKey));
    }

    /** The upload the browser would have done, into the currently active bucket. */
    public void putObject(String objectKey, long size) {
        putObject(bucket, objectKey, size);
    }

    public void putObject(String bucket, String objectKey, long size) {
        objects.put(slot(bucket, objectKey), size);
    }

    /** An upload with a payload, for the callers that read the bytes back. */
    public void putBytes(String objectKey, byte[] content) {
        objects.put(slot(bucket, objectKey), (long) content.length);
        contents.put(slot(bucket, objectKey), content);
    }

    public boolean hasObject(String objectKey) {
        return objects.containsKey(slot(bucket, objectKey));
    }

    public void switchActiveProvider(String provider, String bucket) {
        this.provider = provider;
        this.bucket = bucket;
    }

    /** Runs inside {@link #headSize}, before it answers. */
    public void onHead(Consumer<String> hook) {
        this.onHead = hook;
    }

    public void failHeadWith(RuntimeException failure) {
        this.headFailure = failure;
    }

    /**
     * Makes every delete throw, for the sweeper's retry path.
     *
     * <p>An unreachable provider is the case that matters: the queue row must survive it and be
     * pushed forward, because the alternative — dropping the row — leaves an object nobody will
     * ever delete and nobody can find.
     */
    public void failDeleteWith(RuntimeException failure) {
        this.deleteFailure = failure;
    }

    public void reset() {
        objects.clear();
        contents.clear();
        provider = "R2";
        bucket = "test-ai-documents";
        onHead = key -> { };
        headFailure = null;
        deleteFailure = null;
    }

    private static String slot(String bucket, String objectKey) {
        return bucket + "/" + objectKey;
    }
}
