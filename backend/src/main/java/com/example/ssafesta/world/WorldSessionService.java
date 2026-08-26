package com.example.ssafesta.world;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.user.AccountStatus;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands a client the address of the world and a one-time grant to enter it (spec 002 FR-005).
 *
 * <p>Members and guests both get one: a guest session exists in order to look around (헌법 12조), and
 * infra-003 makes "회원과 게스트 모두" part of its P0 acceptance. What differs is where the identity
 * comes from — a member's row, or the token subject alone, since a guest has no row to read and
 * creating one would make the guest persistent.
 *
 * <p>Nothing is written. This service reads an account at most, and the session it describes lives
 * only inside the grant it just signed (data-model §1).
 */
@Service
public class WorldSessionService {

    private static final Logger log = LoggerFactory.getLogger(WorldSessionService.class);

    private static final String MEMBER_ROLE = "MEMBER";
    private static final String GUEST_ROLE = "GUEST";

    /** Length of the guest suffix shown to other players — enough to tell two guests apart. */
    private static final int GUEST_TAG_LENGTH = 4;

    private final UserRepository users;
    private final WorldEntryTokenIssuer tokens;
    private final WorldProperties properties;

    public WorldSessionService(UserRepository users, WorldEntryTokenIssuer tokens, WorldProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public WorldSessionResponse open(Jwt jwt) {
        if (jwt == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String sessionId = "ws_" + UUID.randomUUID();
        WorldEntryTokenIssuer.WorldIdentity identity = identityOf(jwt);
        WorldEntryTokenIssuer.IssuedGrant grant = tokens.issue(identity, sessionId);

        // Never the grant itself, the secret, or the nickname (FR-023). The pair below is what an
        // operator needs to line a refused admission on the game server up with its issue here.
        log.info("world session issued sessionId={} role={}", sessionId, identity.role());

        return new WorldSessionResponse(sessionId, WorldProperties.WORLD_ID, WorldProperties.CHANNEL_ID,
                new WorldEndpoint(properties.scheme(), properties.host(), properties.port()),
                grant.token(), grant.expiresAt());
    }

    /**
     * Every claim in the grant is derived here, never taken from the request (헌법 16조). The game
     * server is told to ignore what the client asserts and read these instead, which only holds if
     * the client never had a say in them.
     */
    private WorldEntryTokenIssuer.WorldIdentity identityOf(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (MEMBER_ROLE.equals(role)) {
            return memberIdentity(jwt);
        }
        if (GUEST_ROLE.equals(role)) {
            return guestIdentity(jwt);
        }
        // Neither role: a token this server did not issue, or one issued before roles existed.
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }

    private WorldEntryTokenIssuer.WorldIdentity memberIdentity(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.INVALID_MEMBER_TOKEN);
        }
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != AccountStatus.ACTIVE) {
            // The contract answers 403 here ("Account cannot enter the world"). A suspended account
            // keeps its unexpired Access Token, so without this check the suspension would not
            // reach the world at all.
            throw new ApiException(ErrorCode.FORBIDDEN, "정지된 계정은 월드에 입장할 수 없습니다.");
        }
        return new WorldEntryTokenIssuer.WorldIdentity(jwt.getSubject(), MEMBER_ROLE, String.valueOf(user.getId()),
                user.getNickname(), user.getAvatarCode());
    }

    /**
     * A guest has no stored nickname, so one is derived from the token subject — stable for the life
     * of that guest token, which is as long as the guest exists (헌법 12조). No appearance either:
     * null tells Unity to use its default rather than a server-invented preset.
     */
    private WorldEntryTokenIssuer.WorldIdentity guestIdentity(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return new WorldEntryTokenIssuer.WorldIdentity(subject, GUEST_ROLE, subject, guestNicknameOf(subject), null);
    }

    private static String guestNicknameOf(String subject) {
        String tail = subject.substring(subject.indexOf(':') + 1);
        return "게스트-" + tail.substring(0, Math.min(GUEST_TAG_LENGTH, tail.length()));
    }

    /** Matches the {@code WorldSession} schema of {@code world-session.openapi.yaml} field for field. */
    public record WorldSessionResponse(String sessionId, String worldId, String channelId, WorldEndpoint endpoint,
                                       String connectionToken, Instant expiresAt) { }

    /**
     * Structured on purpose rather than one URL string: Unity's transport needs the scheme separately
     * to decide whether to turn encryption on, and the host separately to validate the certificate
     * against (`ConnectionManager` does exactly that). The {@code serverEndpoint} string in
     * `docs/08` §16 is an older draft — infra-003 T070 owns aligning it.
     */
    public record WorldEndpoint(String scheme, String host, int port) { }
}
