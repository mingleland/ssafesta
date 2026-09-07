package com.example.ssafesta.ai;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The contract call over HTTP (S15P21A604-175).
 *
 * <p>{@code RestClient} rather than a new dependency — {@code spring-web} is already on the
 * classpath through {@code spring-boot-starter-webmvc}, and this is the process's only outbound
 * HTTP call. {@code WebClient} would need {@code spring-webflux} for nothing: one request, no
 * streaming, no reactive caller.
 *
 * <p>The builder arrives configured rather than being built here, so a test can hand in one bound
 * to {@code MockRestServiceServer} and assert the path, the header and every status branch without
 * a socket. Transport settings belong to {@link AiProcessingConfiguration} for the same reason.
 */
class HttpDocumentProcessingClient implements DocumentProcessingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDocumentProcessingClient.class);

    /** The contract owns the path; only the origin is configuration. */
    private static final String PATH = "/ai/v1/documents/process";

    private static final int ACCEPTED = 202;

    private final RestClient http;
    private final InternalTokenProperties tokens;

    HttpDocumentProcessingClient(RestClient.Builder builder, InternalTokenProperties tokens) {
        this.tokens = tokens;
        this.http = builder.build();
    }

    @Override
    public void startProcessing(ProcessingRequest request) {
        int status;
        try {
            status = http.post()
                    .uri(PATH)
                    // The first configured value, by the rotation convention (GitLab #102).
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.springToAiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    // Every non-2xx is ours to read rather than throw on. 401 is a configuration
                    // fault and 422 a contract fault, and both deserve the same loud retryable
                    // failure — the default handler would bury which one it was.
                    .onStatus(code -> true, (rq, rs) -> { })
                    .toBodilessEntity()
                    .getStatusCode().value();
        } catch (RestClientException failure) {
            // Class name, not the message: the message carries the resolved URL.
            log.warn("문서 처리 위임 호출이 실패했습니다 jobId={} attemptNo={} 원인={}",
                    request.jobId(), request.attemptNo(), failure.getClass().getSimpleName());
            throw new DocumentProcessingUnavailableException(
                    "문서 처리 서버에 연결할 수 없습니다. 잠시 후 다시 시도됩니다.");
        }
        if (status != ACCEPTED) {
            // The status only. A body from this endpoint carries the AI side's own error text and a
            // 401's WWW-Authenticate echoes what was presented — neither belongs in our logs, for
            // the reason S3ObjectStorage spells out.
            log.warn("문서 처리 위임이 거부됐습니다 jobId={} attemptNo={} status={}",
                    request.jobId(), request.attemptNo(), status);
            throw new DocumentProcessingUnavailableException(
                    "문서 처리 서버가 요청을 받지 못했습니다. 잠시 후 다시 시도됩니다.");
        }
    }
}
