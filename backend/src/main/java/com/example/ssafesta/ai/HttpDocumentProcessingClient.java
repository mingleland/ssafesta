package com.example.ssafesta.ai;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The contract call over HTTP (S15P21A604-175).
 *
 * <p>{@code RestClient} rather than a new dependency — {@code spring-web} is already on the
 * classpath through {@code spring-boot-starter-webmvc}, and this is the process's only outbound
 * HTTP call. {@code WebClient} would need {@code spring-webflux} for nothing: one request, no
 * streaming, no reactive caller.
 */
class HttpDocumentProcessingClient implements DocumentProcessingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDocumentProcessingClient.class);

    /** The contract owns the path; only the origin is configuration. */
    private static final String PATH = "/ai/v1/documents/process";

    private static final int ACCEPTED = 202;

    /**
     * Short on purpose. The call only has to be <i>accepted</i> — FastAPI answers 202 before it
     * parses anything — so a slow answer means the network or the process is unhealthy, and waiting
     * longer only holds the request thread of somebody's upload-complete. A missed delivery is cheap
     * here: the sweeper resends within 30 seconds.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;
    private final InternalTokenProperties tokens;

    HttpDocumentProcessingClient(AiProcessingProperties properties, InternalTokenProperties tokens) {
        this.tokens = tokens;
        this.http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory())
                .build();
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

    /**
     * The JDK client, configured explicitly.
     *
     * <p>Boot 4 moved {@code RestClient.Builder} autoconfiguration and {@code spring.http.client.*}
     * into a module {@code spring-boot-starter-webmvc} does not bring, so there is no ambient
     * builder to inherit timeouts from. Without this the read would have no timeout at all.
     */
    private static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
