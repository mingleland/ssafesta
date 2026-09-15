package com.example.ssafesta.world;

import java.util.Base64;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the world server is and what signs the grant to enter it (spec 002 FR-005·FR-010).
 *
 * <p>Every value here is configuration rather than a constant because 헌법 8조 only works if it is:
 * Unity is forbidden from hardcoding the server address, and that promise is empty if the response
 * it reads from is itself hardcoded one layer down. SC-003 states the test — change the address and
 * clients follow without a rebuild.
 *
 * <p><b>Validation happens at startup and refuses to continue.</b> A wrong scheme or a short secret
 * is not something to discover on a user's first entry attempt; it is the same failure shape as
 * T-24, where a silently swallowed problem looked like nothing happening at all.
 *
 * @param scheme               {@code ws} locally, {@code wss} in any deployed environment (헌법 6조)
 * @param host                 the address Unity connects to — {@code world.<domain>} when deployed
 * @param port                 7777 behind nothing, 443 behind Nginx
 * @param connectionTokenSecret Base64 HMAC key used <b>only</b> for world entry grants. Deliberately
 *                             not {@code app.auth.jwt-secret}: the game server must verify grants on
 *                             its own (헌법 14조), so it holds this key — and a container that holds
 *                             the Access Token key could mint Access Tokens
 */
@ConfigurationProperties("app.world")
public record WorldProperties(String scheme, String host, int port, String connectionTokenSecret) {

    /** MVP is a single floor and a single channel (FR-011, Issue #31). */
    public static final String WORLD_ID = "11F";
    public static final String CHANNEL_ID = "11F-01";

    private static final Set<String> SCHEMES = Set.of("ws", "wss");

    /**
     * The shortest key HS256 may use. 32 bytes is the algorithm's own output size — anything shorter
     * weakens the signature rather than merely looking untidy, so it is rejected outright
     * (`world-entry-token.md`).
     */
    private static final int MIN_SECRET_BYTES = 32;

    public WorldProperties {
        if (scheme == null || !SCHEMES.contains(scheme)) {
            throw new IllegalStateException("app.world.scheme must be 'ws' or 'wss' but was: " + scheme);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("app.world.host must be set.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("app.world.port must be 1..65535 but was: " + port);
        }
        if (decodedSecretLength(connectionTokenSecret) < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.world.connection-token-secret must decode to at least " + MIN_SECRET_BYTES + " bytes.");
        }
    }

    /**
     * @return the decoded length, or {@code -1} when the value is absent or not Base64 — both are
     *         configuration errors, and telling them apart in the message would print part of a
     *         secret to the log
     */
    private static int decodedSecretLength(String secret) {
        if (secret == null || secret.isBlank()) {
            return -1;
        }
        try {
            return Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException notBase64) {
            return -1;
        }
    }

    public byte[] decodedConnectionTokenSecret() {
        return Base64.getDecoder().decode(connectionTokenSecret);
    }
}
