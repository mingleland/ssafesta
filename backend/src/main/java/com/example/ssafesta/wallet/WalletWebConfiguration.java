package com.example.ssafesta.wallet;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the daily grant check on every API request. It runs after the security filter chain,
 * so the interceptor can read the authenticated member from the SecurityContext.
 */
@Configuration
@EnableConfigurationProperties(WalletProperties.class)
public class WalletWebConfiguration implements WebMvcConfigurer {

    private final DailyCoinGrantService dailyGrants;

    public WalletWebConfiguration(DailyCoinGrantService dailyGrants) {
        this.dailyGrants = dailyGrants;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DailyCoinGrantInterceptor(dailyGrants)).addPathPatterns("/api/v1/**");
    }
}
