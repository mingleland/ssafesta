package com.example.ssafesta.auth;

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

    public OAuthHandoffService(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
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

    /**
     * Reads the pending signup <b>without</b> spending it — {@link #discard} does that, once the
     * member actually exists.
     *
     * <p>Deleting on read is right for the member handoff, where nothing after it can fail. Signup
     * can: the nickname may be taken or refused, and FR-021c says the handoff is spent only on a
     * valid submission. Reading it destructively meant a rejected nickname burned it, so the very
     * retry the 409 invites came back {@code OAUTH_HANDOFF_EXPIRED} and the person had to start the
     * whole OAuth round trip over (raised in review of !56).
     *
     * <p>Reading without deleting is only half of it — {@link #discard} has to carry the
     * one-use guarantee that {@code getAndDelete} used to, or two callers holding the same handoff
     * both get a session.
     */
    public PendingRegistration peekRegistration(String handoff) {
        String[] fields = read(handoff, Kind.REGISTRATION, 3);
        return new PendingRegistration(OAuthProvider.valueOf(fields[1]), decode(fields[2]));
    }

    /**
     * Spends a handoff that {@link #peekRegistration} read, and reports whether this caller is the
     * one that spent it.
     *
     * <p>{@code DEL} is atomic and answers how many keys it removed, so among callers racing on the
     * same handoff exactly one sees {@code true}. That single bit is what keeps a handoff worth one
     * session: the caller must not issue one unless it won, because
     * {@link MemberSessionService#issue} <b>revokes the account's previous session</b> — a replay
     * would not merely mint a second session, it would cut the first one off.
     *
     * <p>Returning void here was how the read-then-delete split first went in, and it silently
     * dropped the one-use guarantee that {@code getAndDelete} had been providing (raised in review
     * of !56).
     */
    public boolean discard(String handoff) {
        boolean spent = Boolean.TRUE.equals(redis.delete(key(handoff)));
        if (spent) {
            log.info("Consumed OAuth handoff type={}", Kind.REGISTRATION);
        }
        return spent;
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

    private String key(String handoff) { return PREFIX + handoff; }
    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }

    public enum Kind { MEMBER, REGISTRATION }
    public record PendingRegistration(OAuthProvider provider, String providerSubject) { }
}
