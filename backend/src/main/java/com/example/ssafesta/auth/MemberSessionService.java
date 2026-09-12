package com.example.ssafesta.auth;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 회원의 Access·Refresh 세션을 Redis 에 들고 있는 곳.
 *
 * <h2>폐기 사유와 로그인 계보를 함께 본다 (S15P21A604-660)</h2>
 *
 * <p>spec 001 은 서로 다른 두 가지를 동시에 요구한다.
 *
 * <ul>
 *   <li>시나리오 4 — 같은 계정으로 브라우저 B 에서 새 로그인을 하면 A 는 끝나고
 *       <b>B 의 새 세션만</b> 쓸 수 있다.
 *   <li>시나리오 7 — 이미 <b>교체되어</b> 폐기된 Refresh Token 이 다시 쓰이면 관련 인증 세션을
 *       차단한다.
 * </ul>
 *
 * <p>사유를 구분하지 않으면 시나리오 7 의 처분이 시나리오 4 의 보장을 덮어쓴다. A 가 들고 있던
 * 옛 토큰을 뒤늦게 한 번 보내는 것만으로 B 가 로그아웃됐다. 그래서 폐기된 토큰마다
 * <b>왜 폐기됐는지</b>와 <b>어느 로그인 계보에 속했는지</b>를 같이 적는다.
 *
 * <ul>
 *   <li>{@code SUPERSEDED} — 새 로그인이나 로그아웃에 밀려난 토큰. 재사용돼도 <b>401 만</b> 낸다.
 *       이미 죽은 토큰이고, 지금 살아 있는 세션과는 아무 관계가 없다.
 *   <li>{@code ROTATED} — 정상 회전으로 소비된 토큰. 재사용되면 <b>그 계보가 아직 현행일 때만</b>
 *       세션을 끊는다.
 * </ul>
 *
 * <p><b>사유만으로는 부족하다.</b> A 가 한 번 갱신한 뒤 B 가 새로 로그인하면, A 의 최초 토큰도
 * "회전 소비분" 이라서 사유만 보면 여전히 B 를 죽인다. 계보를 함께 봐야 그 토큰이 이미 끝난
 * 계보의 것임을 알 수 있다. 계보 식별자를 따로 두는 이유는 {@code sessionId} 가 갱신마다 새로
 * 발급되어 연속 회전을 하나로 묶지 못하기 때문이다.
 *
 * <h2>구형 값 호환</h2>
 *
 * <p>{@code app.auth.refresh-token-ttl} 이 {@code P7D} 라 배포 뒤 최대 7일 동안 구형 값이 남는다.
 * 키마다 처분이 다르다 — 하나로 묶으면 "구형 토큰도 갱신된다" 와 "필드가 모자라면 401" 이 서로를
 * 부순다.
 *
 * <ul>
 *   <li>{@code auth:refresh} = {@code userId:sessionId} — <b>살아 있는 토큰</b>이다. 갱신 1회를
 *       허용하고 그 자리에서 계보를 붙여 새 포맷으로 올린다.
 *   <li>{@code auth:refresh:used} = {@code userId} — 사유도 계보도 알 수 없다. <b>401 만</b> 내고
 *       현재 세션은 건드리지 않는다. 모르는 것은 차단의 근거가 아니다.
 * </ul>
 */
@Service
public class MemberSessionService {

    /** 새 로그인·로그아웃에 밀려난 토큰. 재사용해도 현재 세션을 건드리지 않는다. */
    private static final String SUPERSEDED = "SUPERSEDED";

    /** 정상 회전으로 소비된 토큰. 같은 계보에서 재사용되면 그 계보를 끊는다 (spec 001 시나리오 7). */
    private static final String ROTATED = "ROTATED";

    /**
     * 계보 대조와 세션 삭제를 한 번에 한다.
     *
     * <p>둘을 갈라 놓으면 대조를 통과한 직후 B 가 로그인하는 창이 생기고, 뒤이은 삭제가 방금 만든
     * B 의 세션을 지운다 — 이 클래스가 고치려는 결함이 그대로 재현된다. 그래서 "지금 활성 계보가
     * 내가 본 그 계보일 때만 지운다" 를 하나의 원자 연산으로 둔다.
     *
     * <p>계보 키가 없으면 {@code GET} 이 {@code false} 를 주고 비교가 실패한다 — 끊을 계보가 없다는
     * 뜻이므로 아무것도 지우지 않는 것이 맞다.
     *
     * <p>해시로 만드는 키 이름이 {@code KEYS} 가 아니라 {@code ARGV} 접두사로 들어간다. 단일
     * 인스턴스 Redis 전제이며, 클러스터로 가면 이 스크립트부터 손봐야 한다.
     */
    private static final RedisScript<Long> REVOKE_FAMILY = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local hash = redis.call('GET', KEYS[2])
            if hash then
              local refreshKey = ARGV[2] .. hash
              if redis.call('GET', refreshKey) then
                redis.call('SET', ARGV[3] .. hash, ARGV[4], 'EX', ARGV[5])
                redis.call('DEL', refreshKey)
              end
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """, Long.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;
    private final String keyspace;

    public MemberSessionService(StringRedisTemplate redis, AccessTokenService accessTokens, AuthProperties properties,
            RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.keyspace = keyspace.prefix();
    }

    /** 새 로그인. 새 계보를 시작한다 — 이전 계보의 토큰은 이 시점부터 {@code SUPERSEDED} 다. */
    public MemberSession issue(Long userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    /** 발급 본체. 회전은 계보를 물려받고, 새 로그인은 새 계보를 들고 들어온다. */
    private MemberSession issue(Long userId, String familyId) {
        String activeKey = activeKey(userId);
        String previousHash = redis.opsForValue().get(activeKey);
        if (previousHash != null) {
            markSuperseded(previousHash);
            redis.delete(refreshKey(previousHash));
        }
        String rawRefreshToken = randomToken();
        String hash = sha256(rawRefreshToken);
        String sessionId = UUID.randomUUID().toString();
        // 계보를 가장 먼저 쓴다. 이 네 줄은 원자적이지 않아서, 나중에 쓰면 그 사이에 도착한
        // revokeReusedFamily 가 옛 계보와 대조를 통과한 뒤 방금 쓴 active·session 을 지운다 —
        // 고치려던 결함이 순서만 바꿔 되살아난다. 계보가 먼저 보이면 그 대조가 반드시 실패한다.
        redis.opsForValue().set(familyKey(userId), familyId, properties.refreshTokenTtl());
        redis.opsForValue().set(refreshKey(hash), userId + ":" + sessionId + ":" + familyId,
                properties.refreshTokenTtl());
        redis.opsForValue().set(activeKey, hash, properties.refreshTokenTtl());
        redis.opsForValue().set(sessionKey(userId), sessionId, properties.refreshTokenTtl());
        AccessTokenService.IssuedAccessToken access = accessTokens.issueMemberToken(userId, sessionId);
        return new MemberSession(access.token(), access.expiresAt(), rawRefreshToken);
    }

    /**
     * Refresh Token 하나를 새 쌍으로 바꾼다.
     *
     * <p>읽기가 키를 <b>소비</b>한다. {@code get} 뒤에 {@code delete} 를 따로 부르면 같은 토큰을 든 두
     * 요청이 모두 검사를 통과해 각각 발급받는다 — 재사용 감지가 켜지지도 않고 지나가는 것이 바로
     * 그 재사용이다. {@code GETDEL} 이 값을 돌려준 쪽만 계속 간다. {@link OAuthHandoffService} 도
     * 같은 이유로 handoff 를 같은 방식으로 소비한다 (S15P21A604-485).
     */
    public MemberSession refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        String stored = redis.opsForValue().getAndDelete(refreshKey(hash));
        if (stored == null) {
            revokeReusedFamily(redis.opsForValue().get(reusedKey(hash)));
            throw new InvalidRefreshTokenException();
        }
        Session session = Session.parse(stored);
        if (session == null || !isActive(session.userId(), session.sessionId())) {
            throw new InvalidRefreshTokenException();
        }
        // 구형 값에는 계보가 없다. 이 갱신이 그 계보의 시작이 된다 — 소비된 토큰도 같은 계보로
        // 적어야 시나리오 7 이 구형 세션에도 그대로 선다.
        String familyId = session.familyId() != null ? session.familyId() : UUID.randomUUID().toString();
        redis.opsForValue().set(reusedKey(hash), ROTATED + ":" + session.userId() + ":" + familyId,
                properties.refreshTokenTtl());
        return issue(session.userId(), familyId);
    }

    /**
     * 이 회원의 세션을 조건 없이 끝낸다 — 로그아웃·탈퇴·계정 정지가 부른다.
     *
     * <p>계보를 보지 않는 것이 맞다. 세 경로 모두 "지금 로그인한 그 사람을 끝낸다" 는 뜻이고,
     * 계보 대조는 <b>남이 든 죽은 토큰</b>이 현재 세션을 끌어내리는 것을 막으려는 장치다.
     */
    public void revoke(Long userId) {
        String hash = redis.opsForValue().get(activeKey(userId));
        if (hash != null) {
            markSuperseded(hash);
            redis.delete(refreshKey(hash));
        }
        redis.delete(activeKey(userId));
        redis.delete(sessionKey(userId));
        redis.delete(familyKey(userId));
    }

    public boolean isActive(Long userId, String sessionId) {
        return sessionId != null && sessionId.equals(redis.opsForValue().get(sessionKey(userId)));
    }

    /**
     * 폐기된 토큰이 다시 온 자리. 끊을지 말지는 <b>사유와 계보</b>가 정한다.
     *
     * <p>{@code SUPERSEDED} 는 이미 밀려난 토큰이라 지금 세션과 무관하고, 구형 값은 사유도 계보도
     * 없어 판단 근거가 없다. 둘 다 호출자가 던지는 401 로 끝난다.
     */
    private void revokeReusedFamily(String usedValue) {
        if (usedValue == null) {
            return;
        }
        Used used = Used.parse(usedValue);
        if (used == null || !ROTATED.equals(used.reason())) {
            return;
        }
        redis.execute(REVOKE_FAMILY,
                List.of(familyKey(used.userId()), activeKey(used.userId()), sessionKey(used.userId())),
                used.familyId(), refreshKeyPrefix(), reusedKeyPrefix(),
                SUPERSEDED + ":" + used.userId() + ":" + used.familyId(),
                String.valueOf(properties.refreshTokenTtl().toSeconds()));
    }

    private String activeKey(Long userId) { return keyspace + "auth:active:" + userId; }
    private String refreshKey(String hash) { return refreshKeyPrefix() + hash; }
    private String reusedKey(String hash) { return reusedKeyPrefix() + hash; }
    private String sessionKey(Long userId) { return keyspace + "auth:session:" + userId; }
    private String familyKey(Long userId) { return keyspace + "auth:family:" + userId; }
    private String refreshKeyPrefix() { return keyspace + "auth:refresh:"; }
    private String reusedKeyPrefix() { return keyspace + "auth:refresh:used:"; }

    /** 살아 있는 토큰을 "밀려남" 으로 적는다. 이미 소비된 해시라면 기록이 없으므로 아무것도 안 한다. */
    private void markSuperseded(String hash) {
        Session session = Session.parse(redis.opsForValue().get(refreshKey(hash)));
        if (session == null) {
            return;
        }
        // 계보는 SUPERSEDED 판정에 쓰이지 않는다. 구형 값이라 계보를 모르면 빈 칸으로 둔다 —
        // 자리를 지켜야 세 칸 파싱이 성립한다.
        String familyId = session.familyId() != null ? session.familyId() : "";
        redis.opsForValue().set(reusedKey(hash), SUPERSEDED + ":" + session.userId() + ":" + familyId,
                properties.refreshTokenTtl());
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

    /**
     * {@code auth:refresh} 의 값. 구형은 {@code userId:sessionId} 두 칸이고 {@code familyId} 가
     * {@code null} 이다 — 살아 있는 토큰이므로 거부하지 않는다.
     */
    private record Session(Long userId, String sessionId, String familyId) {

        static Session parse(String value) {
            if (value == null) {
                return null;
            }
            String[] fields = value.split(":", 3);
            if (fields.length < 2) {
                return null;
            }
            Long userId = parseUserId(fields[0]);
            return userId == null ? null : new Session(userId, fields[1], fields.length == 3 ? fields[2] : null);
        }
    }

    /**
     * {@code auth:refresh:used} 의 값. 구형은 {@code userId} 한 칸이라 사유도 계보도 없다 —
     * {@code null} 로 접고 호출자가 401 만 낸다.
     */
    private record Used(String reason, Long userId, String familyId) {

        static Used parse(String value) {
            String[] fields = value.split(":", 3);
            if (fields.length != 3) {
                return null;
            }
            Long userId = parseUserId(fields[1]);
            return userId == null ? null : new Used(fields[0], userId, fields[2]);
        }
    }

    /** 손상된 값을 500 이 아니라 401 로 떨어뜨린다 — 이 값들은 전부 우리가 쓴 것이라 정상 경로가 아니다. */
    private static Long parseUserId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public record MemberSession(String accessToken, Instant expiresAt, String refreshToken) {
    }
}
