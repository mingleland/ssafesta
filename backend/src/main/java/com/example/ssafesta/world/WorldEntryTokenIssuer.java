package com.example.ssafesta.world;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the one-time grant that admits a player to the world (spec 002 FR-005, contract
 * {@code world-entry-token.md}).
 *
 * <p><b>This class builds its own encoder on purpose.</b> Injecting the {@code JwtEncoder} bean from
 * {@code JwtConfiguration} would sign world grants with {@code JWT_SECRET} — the Access Token key.
 * The game server has to verify grants without calling Spring (헌법 14조, FR-013), so whatever key
 * signs them ends up inside a deployed game container; if that key were also the Access Token key, a
 * leaked container could mint Access Tokens for any account. With a dedicated key the worst case is
 * bounded to forged world entry.
 *
 * <p>Nothing is stored. Blocking a reused grant is the game server's job — it consumes {@code jti}
 * against its own ledger before creating a player, and that ledger survives redeploys (FR-014).
 * A "used tokens" table here would either go unread or, once read, put Spring back on the admission
 * path that 헌법 14조 exists to keep it off.
 */
@Component
public class WorldEntryTokenIssuer {

    /**
     * Fixed by contract, and short because the grant is single-use.
     *
     * <p>C-04 settled the ordering that makes 120s safe: Unity requests the grant <b>after</b> its
     * WebGL loading finishes, so the clock does not run during a load that can take tens of seconds.
     */
    static final Duration TTL = Duration.ofSeconds(120);

    private static final String ISSUER = "ssafesta-backend";
    private static final String AUDIENCE = "ssafesta-world";

    private final JwtEncoder encoder;

    public WorldEntryTokenIssuer(WorldProperties properties) {
        SecretKey key = new SecretKeySpec(properties.decodedConnectionTokenSecret(), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    public IssuedGrant issue(WorldIdentity identity, String sessionId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TTL);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(identity.subject())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", identity.role())
                .claim("playerId", identity.playerId())
                .claim("nickname", identity.nickname())
                .claim("sessionId", sessionId)
                .claim("worldId", WorldProperties.WORLD_ID)
                .claim("channelId", WorldProperties.CHANNEL_ID);
        if (identity.avatarCode() != null) {
            claims.claim("avatarCode", identity.avatarCode());
        }
        return new IssuedGrant(encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue(), expiresAt);
    }

    /**
     * The verified identity a grant carries. Every field is derived on the server — from the account
     * row for a member, from the Access Token subject for a guest — because 헌법 16조 forbids
     * trusting a userId, nickname or appearance the client asserts, and the game server is told to
     * ignore client-supplied identity in favour of these claims.
     *
     * @param avatarCode the saved appearance, or {@code null} for a guest or a member who never saved
     *                   one. A null is left <b>out</b> of the token rather than encoded as an empty
     *                   string — an empty appearance code is not a value Unity can decode, whereas an
     *                   absent claim already means "fall back to the default" on that side
     */
    public record WorldIdentity(String subject, String role, String playerId, String nickname, String avatarCode) { }

    public record IssuedGrant(String token, Instant expiresAt) { }
}
