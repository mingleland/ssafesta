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
     */
    private static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
