package com.example.ssafesta.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfiguration {

    @Bean
    public ObjectStorage objectStorage(ObjectStorageProperties properties) {
        return new S3ObjectStorage(properties);
    }
}
