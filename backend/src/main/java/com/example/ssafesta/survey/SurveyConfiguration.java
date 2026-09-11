package com.example.ssafesta.survey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SurveyProperties.class)
public class SurveyConfiguration {
}
