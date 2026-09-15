package com.example.ssafesta.ai;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The outbound half of the internal AI API (S15P21A604-175).
 *
 * <p>An explicit {@code @Bean} rather than a {@code @Component} on the implementation, so a test can
 * put a fake in front of it without the real client being scanned into a second bean — the same
 * arrangement {@code ObjectStorageConfiguration} uses.
 */
@Configuration
@EnableConfigurationProperties(AiProcessingProperties.class)
class AiProcessingConfiguration {

    /**
     * Short on purpose. The call only has to be <i>accepted</i> — FastAPI answers 202 before it
     * parses anything — so a slow answer means the network or the process is unhealthy, and waiting
     * longer only holds the request thread of somebody's upload-complete. A missed delivery is cheap:
     * the dispatch sweeper resends within 30 seconds.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    DocumentProcessingClient documentProcessingClient(AiProcessingProperties properties,
                                                      InternalTokenProperties tokens) {
        return new HttpDocumentProcessingClient(RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory()), tokens);
    }

    /**
     * The JDK client, configured explicitly.
     *
     * <p>Boot 4 moved {@code RestClient.Builder} autoconfiguration and {@code spring.http.client.*}
     * into a module {@code spring-boot-starter-webmvc} does not bring, so there is no ambient builder
     * to inherit timeouts from. Without this the read would have no timeout at all.
     *
     * <p><b>{@code HTTP_1_1} is not a default worth inheriting.</b> The JDK client negotiates
     * {@code HTTP_2} unless told otherwise, and over a plaintext connection that means sending the
     * first request as {@code HTTP/1.1} with an {@code Upgrade: h2c} header. uvicorn does not
     * implement that upgrade and loses the body while parsing it, so FastAPI sees a request with no
     * required fields and answers 422 — every time, for every document (GitLab #161). Nothing here
     * wants HTTP/2: the only callee is one uvicorn process on the internal network, and the call is
     * a single small POST that ends at 202.
     */
    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
