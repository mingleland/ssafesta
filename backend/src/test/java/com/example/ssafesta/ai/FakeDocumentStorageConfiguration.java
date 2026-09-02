package com.example.ssafesta.ai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the S3 adapter for the document tests.
 *
 * <p>{@code @Primary} rather than excluding the real bean: the real one still gets built from the
 * dummy R2 settings, so a configuration mistake that would break startup in production breaks these
 * tests too.
 */
@TestConfiguration
class FakeDocumentStorageConfiguration {

    @Bean
    @Primary
    FakeDocumentStorage fakeDocumentStorage() {
        return new FakeDocumentStorage();
    }
}
