package com.example.ssafesta.ai;

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
 * <p>{@link #onHead} is the seam for the races: it runs while the service is between its read and
 * its write, which is exactly where the expiry sweeper and reconcile land in production. That makes
 * those tests deterministic instead of timing-dependent.
 */
class FakeDocumentStorage implements AiDocumentStorage {

    private final Map<String, Long> objects = new ConcurrentHashMap<>();

    private volatile String provider = "R2";
    private volatile String bucket = "test-ai-documents";
    private volatile Consumer<String> onHead = key -> { };
    private volatile RuntimeException headFailure;

    @Override
    public WriteTarget activeWriteTarget() {
        return new WriteTarget(provider, bucket);
    }

    @Override
    public String presignPut(String provider, String bucket, String objectKey, String contentType,
                             long contentLength) {
        // Shaped like a real presigned URL so a test can tell one issue from the next.
        return "https://fake.storage.test/" + bucket + "/" + objectKey
                + "?sig=" + System.nanoTime() + "&len=" + contentLength;
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

    /** The upload the browser would have done, into the currently active bucket. */
    void putObject(String objectKey, long size) {
        putObject(bucket, objectKey, size);
    }

    void putObject(String bucket, String objectKey, long size) {
        objects.put(slot(bucket, objectKey), size);
    }

    void switchActiveProvider(String provider, String bucket) {
        this.provider = provider;
        this.bucket = bucket;
    }

    /** Runs inside {@link #headSize}, before it answers. */
    void onHead(Consumer<String> hook) {
        this.onHead = hook;
    }

    void failHeadWith(RuntimeException failure) {
        this.headFailure = failure;
    }

    void reset() {
        objects.clear();
        provider = "R2";
        bucket = "test-ai-documents";
        onHead = key -> { };
        headFailure = null;
    }

    private static String slot(String bucket, String objectKey) {
        return bucket + "/" + objectKey;
    }
}
