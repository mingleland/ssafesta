package com.example.ssafesta.auth;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfiguration {

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        SecretKey key = secretKey(properties);
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS512).build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties)).macAlgorithm(MacAlgorithm.HS512).build();
    }

    private SecretKey secretKey(AuthProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.jwtSecret());
        if (secret.length < 64) {
            throw new IllegalStateException("JWT_SECRET must contain at least 64 random bytes.");
        }
        return new SecretKeySpec(secret, "HmacSHA512");
    }
}
