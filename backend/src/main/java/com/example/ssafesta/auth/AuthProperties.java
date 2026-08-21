package com.example.ssafesta.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                             Duration oauthStateTtl, String refreshCookiePath,
                             String frontendBaseUrl, boolean cookieSecure) {
}
