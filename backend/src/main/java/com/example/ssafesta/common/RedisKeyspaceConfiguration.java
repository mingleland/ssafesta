package com.example.ssafesta.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registered here rather than per domain — auth and wallet both key off the same namespace. */
@Configuration
@EnableConfigurationProperties(RedisKeyspaceProperties.class)
public class RedisKeyspaceConfiguration {
}
