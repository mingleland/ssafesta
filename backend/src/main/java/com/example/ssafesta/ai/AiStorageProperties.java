package com.example.ssafesta.ai;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage for AI document originals (spec 007 C-07, C-10).
 *
 * <p>Providers are a map rather than fixed fields because C-10 keeps MinIO as an operator-approved
 * fallback: both are S3-compatible, so adding one is configuration, not code. {@code R2} is the only
 * entry P0 configures.
 *
 * @param uploadBlock         operator gate (FR-031, #100). Anything but {@code NONE} refuses new
 *                            grants and <b>writes no row</b> — a row created while uploads are
 *                            blocked would eat one of the ten slots forever
 * @param activeWriteProvider where new uploads go. Existing objects are read by the provider on
 *                            their own row, never by this value (FR-030)
 * @param presignTtl          how long an upload URL lives (FR-026, 기본 15분)
 */
@ConfigurationProperties("app.ai.storage")
public record AiStorageProperties(UploadBlock uploadBlock, String activeWriteProvider,
                                  Duration presignTtl, Map<String, Provider> providers) {

    /**
     * Why new upload grants are refused, if they are.
     *
     * <p>Two reasons, not one flag, because they do not mean the same thing to the person holding
     * the file. The usage guard blocks at 90% of quota <i>and</i> when its own measurement has gone
     * stale (object-storage-contract "Usage admission"); one is "not this month" and the other is
     * "try again shortly", and a single code would have the user retrying a request that cannot
     * succeed (#100).
     *
     * <p>Set by hand. P0 collects probe evidence only and an operator applies the block — automatic
     * detection is explicitly out of scope until the thresholds are agreed (FR-031).
     */
    public enum UploadBlock {

        /** Uploads are open. */
        NONE,
        /** Usage guard {@code UPLOAD_BLOCKED} — 90% of quota. Retrying does not help: 507. */
        QUOTA_EXCEEDED,
        /** Provider outage or {@code STALE_BLOCKED} — temporary, so 503. */
        UNAVAILABLE
    }

    /**
     * SigV4 refuses to sign a URL that outlives this, so a larger value is not a long-lived link —
     * it is a 500 on the first upload request. Checking it here turns a runtime surprise into a
     * startup failure that names the setting.
     */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

    public AiStorageProperties {
        uploadBlock = uploadBlock == null ? UploadBlock.NONE : uploadBlock;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        if (activeWriteProvider == null || activeWriteProvider.isBlank()) {
            throw new IllegalStateException("app.ai.storage.active-write-provider 를 지정해야 합니다.");
        }
        // Fail here rather than on the first upload: a typo would otherwise pass startup and then
        // refuse every grant at runtime, which is a 503 that looks like an outage.
        if (!providers.containsKey(activeWriteProvider)) {
            throw new IllegalStateException("app.ai.storage.active-write-provider 가 providers 에 없습니다: "
                    + activeWriteProvider + " (설정된 provider: " + providers.keySet() + ")");
        }
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            throw new IllegalStateException("app.ai.storage.presign-ttl 은 0보다 커야 합니다: " + presignTtl);
        }
        if (presignTtl.compareTo(MAX_PRESIGN_TTL) > 0) {
            throw new IllegalStateException("app.ai.storage.presign-ttl 은 " + MAX_PRESIGN_TTL
                    + " 이하여야 합니다 (SigV4 상한): " + presignTtl);
        }
        providers.forEach((name, provider) -> provider.requireComplete(name));
    }

    /**
     * One S3-compatible endpoint.
     *
     * <p>No defaults on the credentials, same as {@code JWT_SECRET}: a deployment that forgets them
     * must fail to start rather than sign URLs nobody can use.
     */
    public record Provider(String endpoint, String bucket, String accessKeyId,
                           String secretAccessKey) {

        void requireComplete(String name) {
            require(endpoint, name, "endpoint");
            require(bucket, name, "bucket");
            require(accessKeyId, name, "access-key-id");
            // The value itself never appears in the message — this exception reaches logs.
            require(secretAccessKey, name, "secret-access-key");
        }

        private static void require(String value, String provider, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "app.ai.storage.providers." + provider + "." + field + " 이(가) 비어 있습니다.");
            }
        }
    }
}
