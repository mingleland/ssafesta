package com.example.ssafesta.ai;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The contract calls over HTTP (S15P21A604-175, cancel added by S15P21A604-496).
 *
 * <p>{@code RestClient} rather than a new dependency — {@code spring-web} is already on the
 * classpath through {@code spring-boot-starter-webmvc}, and these are the process's only outbound
 * HTTP calls. {@code WebClient} would need {@code spring-webflux} for nothing: two requests, no
 * streaming, no reactive caller.
 *
 * <p>The builder arrives configured rather than being built here, so a test can hand in one bound
 * to {@code MockRestServiceServer} and assert the path, the header and every status branch without
 * a socket. Transport settings belong to {@link AiProcessingConfiguration} for the same reason.
 */
class HttpDocumentProcessingClient implements DocumentProcessingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDocumentProcessingClient.class);

    /** The contract owns the paths; only the origin is configuration. */
    private static final String PROCESS_PATH = "/ai/v1/documents/process";
    private static final String CANCEL_PATH = "/ai/v1/documents/cancel";

    private static final int ACCEPTED = 202;
    private static final int NO_CONTENT = 204;

    private final RestClient http;
    private final InternalTokenProperties tokens;

    HttpDocumentProcessingClient(RestClient.Builder builder, InternalTokenProperties tokens) {
        this.tokens = tokens;
        this.http = builder.build();
    }

    @Override
    public void startProcessing(ProcessingRequest request) {
        send(PROCESS_PATH, request, ACCEPTED, "문서 처리 위임", request.jobId(), request.attemptNo());
    }

    @Override
    public void cancelProcessing(CancelRequest request) {
        send(CANCEL_PATH, request, NO_CONTENT, "문서 처리 취소", request.jobId(), request.attemptNo());
    }

    /**
     * One POST, its expected status, and the two failures that matter.
     *
     * <p>Both endpoints authenticate the same way and both answer with an empty body, so the only
     * things that differ are the path and which status counts as success. {@code what} is a label
     * for the log — the caller's own words for what it was trying to do, since a bare path tells a
     * reader nothing about whether a document is now stuck or merely still running.
     */
    private void send(String path, Object body, int expected, String what, long jobId, int attemptNo) {
        int status;
        try {
            status = http.post()
                    .uri(path)
                    // The first configured value, by the rotation convention (GitLab #102).
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.springToAiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // Every non-2xx is ours to read rather than throw on. 401 is a configuration
                    // fault and 422 a contract fault, and both deserve the same loud retryable
                    // failure — the default handler would bury which one it was.
                    .onStatus(code -> true, (rq, rs) -> { })
                    .toBodilessEntity()
                    .getStatusCode().value();
        } catch (RestClientException failure) {
            // Class name, not the message: the message carries the resolved URL.
            log.warn("{} 호출이 실패했습니다 jobId={} attemptNo={} 원인={}",
                    what, jobId, attemptNo, failure.getClass().getSimpleName());
            throw new DocumentProcessingUnavailableException(
                    "문서 처리 서버에 연결할 수 없습니다. 잠시 후 다시 시도됩니다.");
        }
        if (status != expected) {
            // The status only. A body from these endpoints carries the AI side's own error text and
            // a 401's WWW-Authenticate echoes what was presented — neither belongs in our logs, for
            // the reason S3ObjectStorage spells out.
            log.warn("{} 요청이 거부됐습니다 jobId={} attemptNo={} status={}", what, jobId, attemptNo, status);
            throw new DocumentProcessingUnavailableException(
                    "문서 처리 서버가 요청을 받지 못했습니다. 잠시 후 다시 시도됩니다.");
        }
    }
}
