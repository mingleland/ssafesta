package com.example.ssafesta.ai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts the fake in front of the real client.
 *
 * <p>{@code @Primary} rather than excluding the real bean: the real one still gets built from the
 * test's dummy base URL, so a configuration mistake that would break startup in production breaks
 * these tests too. The bean type is the concrete fake so tests can read what it recorded.
 */
@TestConfiguration
public class FakeDocumentProcessingClientConfiguration {

    @Bean
    @Primary
    public FakeDocumentProcessingClient fakeDocumentProcessingClient() {
        return new FakeDocumentProcessingClient();
    }
}
