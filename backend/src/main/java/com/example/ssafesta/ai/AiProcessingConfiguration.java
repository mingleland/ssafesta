package com.example.ssafesta.ai;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbound half of the internal AI API (S15P21A604-175).
 *
 * <p>An explicit {@code @Bean} rather than a {@code @Component} on the implementation, so a test
 * can put a fake in front of it without the real client being scanned into a second bean — the same
 * arrangement {@code ObjectStorageConfiguration} uses.
 */
@Configuration
@EnableConfigurationProperties(AiProcessingProperties.class)
class AiProcessingConfiguration {

    @Bean
    DocumentProcessingClient documentProcessingClient(AiProcessingProperties properties,
                                                      InternalTokenProperties tokens) {
        return new HttpDocumentProcessingClient(properties, tokens);
    }
}
