package com.example.ssafesta.game;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GameProperties.class)
class GameConfiguration {
}
