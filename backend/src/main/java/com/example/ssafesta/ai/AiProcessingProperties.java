package com.example.ssafesta.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where FastAPI is (spec 007, S15P21A604-175).
 *
 * <p>Spring calls one endpoint on it — {@code POST /ai/v1/documents/process} — to start processing a
 * Job it has already persisted. Only the origin is configured; the path belongs to the contract
 * ({@code specs/007-ai-agent-document/contracts/document-processing-api.yaml}) and moves with it,
 * not with a deployment.
 *
 * <p>No default. Infra's own manifest already names the value
 * ({@code infra/environments/config/manifests/dev.json}: {@code "internalEndpoint": "http://ai:8000"}),
 * so a missing variable is a deployment that forgot to inject it rather than a value anyone would
 * want guessed. Guessing {@code localhost} would make every delegation fail with a connection error
 * that looks like FastAPI being down.
 *
 * <p>Timeouts are not here. They are operational constants of the one call that uses them and live
 * beside it in {@link HttpDocumentProcessingClient}, the same way the lease and heartbeat seconds
 * live in the repository that writes them.
 *
 * @param baseUrl the origin FastAPI is reachable at, e.g. {@code http://ai:8000}. No trailing slash
 *                is required — the client appends the contract path to whatever is given
 */
@ConfigurationProperties("app.ai.processing")
public record AiProcessingProperties(String baseUrl) {

    public AiProcessingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.ai.processing.base-url 을 지정해야 합니다 (예: http://ai:8000).");
        }
        // An unresolved placeholder passes every check above — "${AI_INTERNAL_BASE_URL}" is neither
        // null nor blank. Left alone it becomes a URL the client cannot resolve, and the failure
        // arrives much later as a delegation error rather than as the missing variable it is. Same
        // reasoning as ObjectStorageProperties, and the same shape as T-101.
        if (baseUrl.startsWith("${") && baseUrl.endsWith("}")) {
            throw new IllegalStateException("app.ai.processing.base-url 이 해석되지 않았습니다 — 배포에서 "
                    + baseUrl + " 를 주입해야 합니다.");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "app.ai.processing.base-url 은 http:// 또는 https:// 로 시작해야 합니다: " + baseUrl);
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
