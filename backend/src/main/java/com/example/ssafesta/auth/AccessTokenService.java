package com.example.ssafesta.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public AccessTokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedAccessToken issueGuestToken() {
        return issue("guest:" + UUID.randomUUID(), "GUEST", null);
    }

    public IssuedAccessToken issueMemberToken(Long userId, String sessionId) {
        return issue(userId.toString(), "MEMBER", sessionId);
    }

    private IssuedAccessToken issue(String subject, String role, String sessionId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(subject).issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).claim("role", role);
        if (sessionId != null) {
            claims.claim("sid", sessionId);
        }
        return new IssuedAccessToken(jwtEncoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue(), expiresAt);
    }

    public record IssuedAccessToken(String token, Instant expiresAt) {
    }
}
