package com.example.ssafesta.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiStorageProperties.class)
class AiStorageConfiguration {

    @Bean
    AiDocumentStorage aiDocumentStorage(AiStorageProperties properties) {
        return new S3DocumentStorage(properties);
    }
}
