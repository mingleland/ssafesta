package com.example.ssafesta.storage;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage for AI document originals (spec 007 C-07, C-10).
 *
 * <p>Providers are a map rather than fixed fields because C-10 keeps MinIO as an operator-approved
 * fallback: both are S3-compatible, so adding one is configuration, not code. {@code R2} is the only
 * entry P0 configures.
 *
 * <p><b>One admission setting, not two.</b> An earlier draft carried the two state machines of the
 * contract verbatim ({@code usage-state} + {@code storage-state}) because both spell one of their
 * values {@code UPLOAD_BLOCKED} while meaning different things by it — a spent quota (507) in one,
 * an outage (503) in the other. GitLab #100 (2026-09-01) settled it the other way: the operator
 * reads both machines and writes the <i>verdict</i> into a single {@link UploadGate}. The server
 * does not model the state machines at all, and the two codes stay distinguishable because the enum
 * names the cause rather than the state.
 *
 * @param uploadGate          whether new grants may be issued, and why not (#100)
 * @param activeWriteProvider where new uploads go. Existing objects are read by the provider on
 *                            their own row, never by this value (FR-030)
 * @param presignTtl          how long an upload URL lives (FR-026, 기본 15분)
 */
@ConfigurationProperties("app.ai.storage")
public record ObjectStorageProperties(UploadGate uploadGate, String activeWriteProvider,
                                  Duration presignTtl, Map<String, Provider> providers) {

    /**
     * SigV4 refuses to sign a URL that outlives this, so a larger value is not a long-lived link —
     * it is a 500 on the first upload request. Checking it here turns a runtime surprise into a
     * startup failure that names the setting.
     */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

    /** FR-030 · document-processing-api.yaml {@code storageProvider}. Not an open vocabulary. */
    private static final Set<String> PROVIDER_NAMES = Set.of("R2", "MINIO_LOCAL");

    /**
     * Whether upload grants may be issued, and — when they may not — which of the two refusals the
     * caller gets (#100, 2026-09-01).
     *
     * <p>The operator maps the observed state onto one of these: normal·warning and a validated
     * {@code LOCAL_ACTIVE} are {@link #OPEN}; 90% of quota is {@link #QUOTA_BLOCKED}; a stale
     * measurement, an R2 outage, {@code FALLBACK_VALIDATING} and {@code R2_RECONCILING} are all
     * {@link #UNAVAILABLE}.
     *
     * <p>Splitting the two refusals is the whole reason this is an enum and not a boolean: telling a
     * user whose quota is spent to "try again shortly" makes them retry forever.
     */
    public enum UploadGate {

        /** Grants are issued. */
        OPEN,
        /** Quota is spent. Retrying does not help, so 507. */
        QUOTA_BLOCKED,
        /** Outage, stale measurement or a transition window. Something to wait out, so 503. */
        UNAVAILABLE
    }

    public ObjectStorageProperties {
        // No default, and that is the point. Spring ignores a property it does not recognise, so a
        // renamed or misspelled key would leave this null, fall back to "admit", and reopen uploads
        // during a block someone believed was in force. A safety control must not be able to fail
        // open through a typo — say it or do not start.
        if (uploadGate == null) {
            throw new IllegalStateException("app.ai.storage.upload-gate 를 지정해야 합니다. 허용값: "
                    + Arrays.toString(UploadGate.values()));
        }
        providers = withoutUnconfigured(providers);
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
     * Drops the entries a deployment left entirely empty.
     *
     * <p>{@code application.yml} lists MinIO so that a fallback is a value change rather than an
     * image change (object-storage-contract §Manual fallback: 설정 변경 + 재배포). A deployment
     * running on R2 alone has no MinIO credentials to give, and four blank-rejecting fields would
     * force it to invent them. <b>All-blank means "not configured"; partially filled still fails</b>
     * through {@link Provider#requireComplete} — a half-set provider is a typo, not a choice.
     *
     * <p>There is no separate state left to disagree with the write provider, so the old
     * {@code storage-state} ↔ {@code active-write-provider} consistency check went with it (#100):
     * the pair {@code (OPEN, R2)} <i>is</i> {@code R2_ACTIVE} and {@code (OPEN, MINIO_LOCAL)}
     * <i>is</i> {@code LOCAL_ACTIVE}. One fact in one place cannot contradict itself.
     */
    private static Map<String, Provider> withoutUnconfigured(Map<String, Provider> configured) {
        if (configured == null) {
            return Map.of();
        }
        return configured.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isUnconfigured())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * One S3-compatible endpoint.
     *
     * <p>No defaults on the credentials, same as {@code JWT_SECRET}: a deployment that forgets them
     * must fail to start rather than sign URLs nobody can use.
     */
    public record Provider(String endpoint, String bucket, String accessKeyId,
                           String secretAccessKey) {

        /** Every field blank — the deployment did not configure this provider at all. */
        boolean isUnconfigured() {
            return blank(endpoint) && blank(bucket) && blank(accessKeyId) && blank(secretAccessKey);
        }

        void requireComplete(String name) {
            require(endpoint, name, "endpoint");
            require(bucket, name, "bucket");
            require(accessKeyId, name, "access-key-id");
            // The value itself never appears in the message — this exception reaches logs.
            require(secretAccessKey, name, "secret-access-key");
        }

        private static void require(String value, String provider, String field) {
            if (blank(value)) {
                throw new IllegalStateException(
                        "app.ai.storage.providers." + provider + "." + field + " 이(가) 비어 있습니다.");
            }
            // 해석되지 않은 placeholder 는 "값이 있다" 로 통과한다. 그대로 두면 서명 키가
            // "${R2_SECRET_ACCESS_KEY}" 인 채로 기동하고, 모든 업로드가 저장소에서 서명 오류로
            // 죽는다 — 오설정이 장애처럼 보이는 바로 그 모양이다. 여기서 잡고 빠진 변수 이름을
            // 그대로 알려 준다. 이때 출력하는 것은 secret 이 아니라 변수 이름이다.
            if (value.startsWith("${") && value.endsWith("}")) {
                throw new IllegalStateException("app.ai.storage.providers." + provider + "." + field
                        + " 이(가) 해석되지 않았습니다 — 배포에서 " + value + " 를 주입해야 합니다.");
            }
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
