package com.example.ssafesta.booth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LeaseProperties.class)
public class BoothConfiguration {
}
