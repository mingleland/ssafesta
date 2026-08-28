package com.example.ssafesta.world;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Same shape as {@code BoothConfiguration}: binds this package's properties and nothing else. */
@Configuration
@EnableConfigurationProperties(WorldProperties.class)
public class WorldConfiguration {
}
