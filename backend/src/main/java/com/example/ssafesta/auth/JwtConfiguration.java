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
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfiguration {

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        SecretKey key = secretKey(properties);
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS512).build();
    }

    /**
     * The timestamp validator is set explicitly so the tolerance is a number this project owns.
     *
     * <p>It is the same 60 seconds Spring applies by default, and the point is not the value — it is
     * that {@code SurveyGuestKeySweeper} reads the same property. That sweeper deletes the key which
     * stops a guest answering twice, and deleting it while this decoder still accepts the token
     * would open exactly the door 1인 1응답 closes. Left implicit, the two numbers can drift without
     * anything failing loudly.
     *
     * <p>Only the timestamp validator is replaced. {@code createDefaultWithValidators} keeps the
     * rest of the default bundle — a hand-built {@code DelegatingOAuth2TokenValidator} would silently
     * drop {@code JwtTypeValidator} and the thumbprint check along with it.
     */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS512).build();
        // createDefaultWithValidators 는 기본 묶음을 유지하면서 같은 종류가 이미 있으면 그것을
        // 쓴다 — JwtTypeValidator·X509CertificateThumbprintValidator 는 그대로 남고 시각 검증만
        // 우리 skew 로 바뀐다. 여기서 DelegatingOAuth2TokenValidator 를 직접 만들면 나머지 기본
        // 검증이 통째로 사라진다.
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtTimestampValidator(properties.jwtClockSkew())));
        return decoder;
    }

    private SecretKey secretKey(AuthProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.jwtSecret());
        if (secret.length < 64) {
            throw new IllegalStateException("JWT_SECRET must contain at least 64 random bytes.");
        }
        return new SecretKeySpec(secret, "HmacSHA512");
    }
}
