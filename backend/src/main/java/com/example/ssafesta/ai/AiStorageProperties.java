package com.example.ssafesta.ai;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage for AI document originals (spec 007 C-07, C-10).
 *
 * <p>Providers are a map rather than fixed fields because C-10 keeps MinIO as an operator-approved
 * fallback: both are S3-compatible, so adding one is configuration, not code. {@code R2} is the only
 * entry P0 configures.
 *
 * <p><b>Two admission states, not one.</b> The contract has two separate machines answering
 * different questions — {@link UsageState} is how full the bucket is, {@link StorageState} is
 * whether the provider is usable at all. They also share the token {@code UPLOAD_BLOCKED} while
 * meaning different things by it: a spent quota (507) in one, an outage (503) in the other. A single
 * setting could not say which was meant, so there are two.
 *
 * @param usageState          usage-guard snapshot state (object-storage-contract §Usage admission)
 * @param storageState        provider failover state (object-storage-contract §Manual fallback)
 * @param activeWriteProvider where new uploads go. Existing objects are read by the provider on
 *                            their own row, never by this value (FR-030)
 * @param presignTtl          how long an upload URL lives (FR-026, 기본 15분)
 */
@ConfigurationProperties("app.ai.storage")
public record AiStorageProperties(UsageState usageState, StorageState storageState,
                                  String activeWriteProvider, Duration presignTtl,
                                  Map<String, Provider> providers) {

    /**
     * SigV4 refuses to sign a URL that outlives this, so a larger value is not a long-lived link —
     * it is a 500 on the first upload request. Checking it here turns a runtime surprise into a
     * startup failure that names the setting.
     */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

    /** FR-030 · document-processing-api.yaml {@code storageProvider}. Not an open vocabulary. */
    private static final Set<String> PROVIDER_NAMES = Set.of("R2", "MINIO_LOCAL");

    /**
     * How much of the quota is used, straight from {@code usage-guard.schema.json}.
     *
     * <p>The names are the schema's, not ours: an operator reads a state off that snapshot and
     * copies it here. All four bind — including {@code WARNING}, which admits. An enum missing one
     * of them would reject a state the contract says can occur, forcing a translation at exactly
     * the moment translations go wrong.
     */
    public enum UsageState {

        /** Under the warning threshold. */
        NORMAL,
        /** 80%. The contract admits uploads here and warns, so admission matches {@link #NORMAL}. */
        WARNING,
        /** 90% of quota. Retrying does not help, so 507 (#100). */
        UPLOAD_BLOCKED,
        /** The measurement itself went stale, so admission fails closed. Temporary: 503. */
        STALE_BLOCKED
    }

    /**
     * Where the provider failover machine stands (object-storage-contract §Manual fallback).
     *
     * <p>Every blocked value here is <b>503</b>: an outage or a validation window is something to
     * wait out, unlike a spent quota. Transitions are operator-driven — P0 has no automatic
     * detection (FR-031).
     */
    public enum StorageState {

        /** Normal operation on R2. */
        R2_ACTIVE,
        /**
         * R2 refused for new grants. Shares its name with {@link UsageState#UPLOAD_BLOCKED} and
         * means something else — which is why the two live in separate settings.
         */
        UPLOAD_BLOCKED,
        /** Proving the fallback before switching to it. No new grants until it is proven. */
        FALLBACK_VALIDATING,
        /** Running on MinIO. Uploads are admitted and land in the configured provider. */
        LOCAL_ACTIVE,
        /**
         * Copying objects back to R2. <b>New uploads stay blocked</b> — docs/26 records that P0
         * operates this state blocked and that an implementer must not open it. Whether it can be
         * opened at all is a later decision, not this one.
         */
        R2_RECONCILING
    }

    public AiStorageProperties {
        // No defaults on either state, and that is the point. Spring ignores a property it does not
        // recognise, so a renamed or misspelled key would leave one null, fall back to "admit", and
        // reopen uploads during a block someone believed was in force. A safety control must not be
        // able to fail open through a typo — say it or do not start.
        if (usageState == null) {
            throw new IllegalStateException("app.ai.storage.usage-state 를 지정해야 합니다. 허용값: "
                    + Arrays.toString(UsageState.values()));
        }
        if (storageState == null) {
            throw new IllegalStateException("app.ai.storage.storage-state 를 지정해야 합니다. 허용값: "
                    + Arrays.toString(StorageState.values()));
        }
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
        // The name is written onto every document row and handed to FastAPI, whose contract types
        // it as an enum of exactly these two. A third name would be stored happily here and then
        // fail to parse there, one hop away from anything that could explain it (FR-030).
        providers.keySet().stream()
                .filter(name -> !PROVIDER_NAMES.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("app.ai.storage.providers 의 이름은 " + PROVIDER_NAMES
                            + " 중 하나여야 합니다 (문서 행과 FastAPI 계약에 그대로 실린다): " + name);
                });
        requireStateMatchesProvider(storageState, activeWriteProvider);
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
     * The failover state and the provider being written to must agree.
     *
     * <p>Two places holding one fact, so refuse to start when they disagree — the same move
     * {@code AiAgentProperties} makes for the per-booth limit and its unique index. An operator who
     * moved the state to {@code LOCAL_ACTIVE} believes uploads now land in MinIO; if the provider
     * were left at {@code R2} they would keep landing in the storage that was just declared
     * unusable, and nothing would say so (object-storage-contract §Manual fallback:
     * "{@code LOCAL_ACTIVE} 에서 생성한 metadata 는 provider {@code MINIO_LOCAL} 을 명시한다").
     *
     * <p>Only the two admitting states are constrained. The blocked ones issue no grants, so there
     * is no write provider for them to disagree with.
     */
    private static void requireStateMatchesProvider(StorageState state, String provider) {
        String expected = switch (state) {
            case R2_ACTIVE -> "R2";
            case LOCAL_ACTIVE -> "MINIO_LOCAL";
            case UPLOAD_BLOCKED, FALLBACK_VALIDATING, R2_RECONCILING -> null;
        };
        if (expected != null && !expected.equals(provider)) {
            throw new IllegalStateException("app.ai.storage.storage-state=" + state
                    + " 이면 active-write-provider 는 " + expected + " 여야 합니다: " + provider);
        }
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
