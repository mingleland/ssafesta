package com.example.ssafesta.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The world entry grant, checked against {@code world-entry-token.md} claim by claim.
 *
 * <p>The load-bearing test here is {@link #theAccessTokenKeyCannotVerifyAGrant()}. Everything else
 * would still pass if someone injected the existing {@code JwtEncoder} bean instead of building a
 * dedicated one — and that mistake would hand the game server the ability to mint Access Tokens
 * (research R-04). No integration test would notice, because the grant would verify fine.
 */
class WorldEntryTokenIssuerTest {

    /** Distinct fixed keys: if the wrong one ever signs a grant, the assertions must diverge. */
    private static final String WORLD_SECRET = base64Of("world-entry-signing-key-for-tests-only-32b+");
    private static final String AUTH_SECRET = base64Of("access-token-signing-key-for-tests-only-32b+");

    private static final WorldEntryTokenIssuer.WorldIdentity MEMBER =
            new WorldEntryTokenIssuer.WorldIdentity("7", "MEMBER", "7", "덕", "fa|3=SK_Hair_Long_01|c=FF8800");
    private static final WorldEntryTokenIssuer.WorldIdentity GUEST =
            new WorldEntryTokenIssuer.WorldIdentity("guest:ab12cd34", "GUEST", "guest:ab12cd34", "게스트-ab12", null);

    private final WorldEntryTokenIssuer issuer = new WorldEntryTokenIssuer(worldProperties(WORLD_SECRET));

    @Test
    void aMemberGrantCarriesEveryContractClaim() {
        Jwt grant = decodeWithWorldKey(issuer.issue(MEMBER, "ws_fixed").token());

        // Read as a plain string: the contract's issuer is a service name, not a URL, and Spring's
        // getIssuer() accessor coerces the claim to a URL and fails on anything else.
        assertEquals("ssafesta-backend", grant.getClaimAsString("iss"));
        assertEquals("ssafesta-world", grant.getAudience().getFirst());
        assertEquals("7", grant.getSubject());
        assertEquals("MEMBER", grant.getClaimAsString("role"));
        assertEquals("7", grant.getClaimAsString("playerId"));
        assertEquals("덕", grant.getClaimAsString("nickname"));
        assertEquals(MEMBER.avatarCode(), grant.getClaimAsString("avatarCode"));
        assertEquals("ws_fixed", grant.getClaimAsString("sessionId"));
        assertEquals("11F", grant.getClaimAsString("worldId"));
        assertEquals("11F-01", grant.getClaimAsString("channelId"));
        assertFalse(grant.getId().isBlank(), "jti is what the game server consumes to block reuse");
    }

    @Test
    void aGuestGrantOmitsTheAppearanceInsteadOfInventingOne() {
        // Absent, not "" — an empty appearance code is not something Unity can decode, whereas a
        // missing claim already means "use the default" on that side.
        Jwt grant = decodeWithWorldKey(issuer.issue(GUEST, "ws_guest").token());

        assertEquals("GUEST", grant.getClaimAsString("role"));
        assertEquals("게스트-ab12", grant.getClaimAsString("nickname"));
        assertNull(grant.getClaim("avatarCode"));
    }

    @Test
    void theGrantLivesForTwoMinutes() {
        // C-04 fixed the ordering that makes this safe: Unity asks after loading, not before.
        Jwt grant = decodeWithWorldKey(issuer.issue(MEMBER, "ws_ttl").token());

        assertEquals(Duration.ofSeconds(120), Duration.between(grant.getIssuedAt(), grant.getExpiresAt()));
    }

    @Test
    void theResponseExpiryAndTheTokenExpiryAreTheSameInstant() {
        // Two clocks would let a client believe it has time the game server will not honour.
        WorldEntryTokenIssuer.IssuedGrant issued = issuer.issue(MEMBER, "ws_expiry");

        assertEquals(issued.expiresAt().getEpochSecond(),
                decodeWithWorldKey(issued.token()).getExpiresAt().getEpochSecond());
    }

    @Test
    void everyGrantGetsItsOwnJti() {
        // Single use is enforced by consuming jti. Two grants sharing one would let the second
        // admission be refused as a replay of the first.
        String first = decodeWithWorldKey(issuer.issue(MEMBER, "ws_a").token()).getId();
        String second = decodeWithWorldKey(issuer.issue(MEMBER, "ws_b").token()).getId();

        assertNotEquals(first, second);
    }

    @Test
    void theAccessTokenKeyCannotVerifyAGrant() {
        String token = issuer.issue(MEMBER, "ws_keys").token();

        NimbusJwtDecoder withAuthKey = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(Base64.getDecoder().decode(AUTH_SECRET), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();

        assertThrows(JwtException.class, () -> withAuthKey.decode(token),
                "the world key must be dedicated — sharing JWT_SECRET would let the game server mint Access Tokens");
    }

    @Test
    void aTooShortSecretStopsStartupInsteadOfWeakeningTheSignature() {
        // Discovering this on a user's first entry attempt is the T-24 shape: a silent problem that
        // looks like nothing happening.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> worldProperties(base64Of("too-short")));

        assertTrue(failure.getMessage().contains("32 bytes"));
        assertFalse(failure.getMessage().contains("too-short"), "a secret must never reach a log or message");
    }

    // ------------------------------------------------------------------ helpers

    private static WorldProperties worldProperties(String secret) {
        return new WorldProperties("ws", "127.0.0.1", 7777, secret);
    }

    private static Jwt decodeWithWorldKey(String token) {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(Base64.getDecoder().decode(WORLD_SECRET), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build()
                .decode(token);
    }

    private static String base64Of(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
