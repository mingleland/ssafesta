package com.example.ssafesta.auth;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import com.example.ssafesta.user.OAuthProvider;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A short-lived OAuth callback result. Its opaque identifier is only sent in an HttpOnly cookie. */
@Service
public class OAuthHandoffService {
    private static final Logger log = LoggerFactory.getLogger(OAuthHandoffService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "auth:oauth-handoff:";
    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    /** Not static — the namespace is configuration, so the prefix can only be built per instance. */
    private final String prefix;

    public OAuthHandoffService(StringRedisTemplate redis, AuthProperties properties,
            RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.properties = properties;
        this.prefix = keyspace.prefix() + PREFIX;
    }

    public String createMember(MemberSessionService.MemberSession session) {
        return store("MEMBER", session.accessToken(), session.refreshToken(), session.expiresAt().toString());
    }

    public String createRegistration(OAuthProvider provider, String providerSubject) {
        return store("REGISTRATION", provider.name(), encode(providerSubject));
    }

    public Kind kind(String handoff) {
        String value = redis.opsForValue().get(key(handoff));
        if (value == null) {
            log.warn("OAuth handoff is absent before completion lookup");
            throw new InvalidOAuthHandoffException();
        }
        return Kind.valueOf(value.substring(0, value.indexOf('|')));
    }

    public MemberSessionService.MemberSession consumeMember(String handoff) {
        String[] fields = consume(handoff, Kind.MEMBER, 4);
        return new MemberSessionService.MemberSession(fields[1], Instant.parse(fields[3]), fields[2]);
    }

    /** Reads without spending — {@link #discard} spends it, once the member exists (FR-021c). */
    public PendingRegistration peekRegistration(String handoff) {
        String[] fields = read(handoff, Kind.REGISTRATION, 3);
        return new PendingRegistration(OAuthProvider.valueOf(fields[1]), decode(fields[2]));
    }

    /** Spends the handoff; {@code true} only for the caller whose DEL removed it — one session per handoff. */
    public boolean discard(String handoff) {
        return Boolean.TRUE.equals(redis.delete(key(handoff)));
    }

    private String store(String... fields) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String handoff = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String key = key(handoff);
        redis.opsForValue().set(key, String.join("|", fields), properties.oauthStateTtl());
        log.info("Created OAuth handoff type={}, stored={}, ttlSeconds={}", fields[0],
                Boolean.TRUE.equals(redis.hasKey(key)), redis.getExpire(key));
        return handoff;
    }

    private String[] consume(String handoff, Kind expected, int fieldCount) {
        String[] fields = parse(redis.opsForValue().getAndDelete(key(handoff)), expected, fieldCount);
        log.info("Consumed OAuth handoff type={}", expected);
        return fields;
    }

    private String[] read(String handoff, Kind expected, int fieldCount) {
        return parse(redis.opsForValue().get(key(handoff)), expected, fieldCount);
    }

    private String[] parse(String value, Kind expected, int fieldCount) {
        if (value == null) {
            log.warn("OAuth handoff was absent when completion tried to consume it");
            throw new InvalidOAuthHandoffException();
        }
        String[] fields = value.split("\\|", fieldCount);
        if (fields.length != fieldCount || !expected.name().equals(fields[0])) throw new InvalidOAuthHandoffException();
        return fields;
    }

    private String key(String handoff) { return prefix + handoff; }
    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }

    public enum Kind { MEMBER, REGISTRATION }
    public record PendingRegistration(OAuthProvider provider, String providerSubject) { }
}
