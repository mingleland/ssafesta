package com.example.ssafesta.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemberSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;

    public MemberSessionService(StringRedisTemplate redis, AccessTokenService accessTokens, AuthProperties properties) {
        this.redis = redis;
        this.accessTokens = accessTokens;
        this.properties = properties;
    }

    public MemberSession issue(Long userId) {
        String activeKey = activeKey(userId);
        String previousHash = redis.opsForValue().get(activeKey);
        if (previousHash != null) {
            markReused(previousHash);
            redis.delete(refreshKey(previousHash));
        }
        String rawRefreshToken = randomToken();
        String hash = sha256(rawRefreshToken);
        String sessionId = UUID.randomUUID().toString();
        redis.opsForValue().set(refreshKey(hash), userId + ":" + sessionId, properties.refreshTokenTtl());
        redis.opsForValue().set(activeKey, hash, properties.refreshTokenTtl());
        redis.opsForValue().set(sessionKey(userId), sessionId, properties.refreshTokenTtl());
        AccessTokenService.IssuedAccessToken access = accessTokens.issueMemberToken(userId, sessionId);
        return new MemberSession(access.token(), access.expiresAt(), rawRefreshToken);
    }

    public MemberSession refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        String session = redis.opsForValue().get(refreshKey(hash));
        if (session == null) {
            String reusedBy = redis.opsForValue().get(reusedKey(hash));
            if (reusedBy != null) {
                revoke(Long.parseLong(reusedBy));
            }
            throw new InvalidRefreshTokenException();
        }
        String[] values = session.split(":", 2);
        if (values.length != 2 || !isActive(Long.parseLong(values[0]), values[1])) {
            throw new InvalidRefreshTokenException();
        }
        redis.delete(refreshKey(hash));
        redis.opsForValue().set(reusedKey(hash), values[0], properties.refreshTokenTtl());
        return issue(Long.parseLong(values[0]));
    }

    public void revoke(Long userId) {
        String hash = redis.opsForValue().get(activeKey(userId));
        if (hash != null) {
            markReused(hash);
            redis.delete(refreshKey(hash));
        }
        redis.delete(activeKey(userId));
        redis.delete(sessionKey(userId));
    }

    public boolean isActive(Long userId, String sessionId) {
        return sessionId != null && sessionId.equals(redis.opsForValue().get(sessionKey(userId)));
    }

    private String activeKey(Long userId) { return "auth:active:" + userId; }
    private String refreshKey(String hash) { return "auth:refresh:" + hash; }
    private String reusedKey(String hash) { return "auth:refresh:used:" + hash; }
    private String sessionKey(Long userId) { return "auth:session:" + userId; }

    private void markReused(String hash) {
        String session = redis.opsForValue().get(refreshKey(hash));
        if (session != null) {
            redis.opsForValue().set(reusedKey(hash), session.substring(0, session.indexOf(':')), properties.refreshTokenTtl());
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record MemberSession(String accessToken, Instant expiresAt, String refreshToken) {
    }
}
