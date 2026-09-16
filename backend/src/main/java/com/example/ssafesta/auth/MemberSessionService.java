package com.example.ssafesta.auth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 거절의 사유를 남긴다 — 봉투로는 갈리지 않는 갈래가 있기 때문이다 (GitLab #211).
     *
     * <p>refresh 401 은 밖에서 보면 {@code INVALID_MEMBER_TOKEN} 하나인데, 안에서는 "쿠키가 안
     * 왔다" · "키가 없다" · "토큰은 멀쩡한데 세션이 없다" 가 전부 여기로 접힌다. 원인이 서로 다른데
     * 사후에 구분할 방법이 없었다. 판정은 바꾸지 않고 사유만 적는다.
     *
     * <p><b>해시도 원문도 적지 않는다</b> (헌법 13조). 남기는 것은 사유와 {@code userId} 까지다.
     */
    private static final Logger log = LoggerFactory.getLogger(MemberSessionService.class);

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

    /**
     * 토큰 소비와 재사용 표식 기록을 한 번에 한다.
     *
     * <p>둘을 갈라 놓으면 <b>표식이 아직 없는 창</b>이 생긴다 — 진 쪽의 {@code GETDEL} 이 이긴 쪽의
     * 표식 기록보다 먼저 끝나면 재사용 판정에 쓸 값이 없고, 그 요청은 "그냥 없는 토큰" 으로 401 을
     * 받는다. 세션은 살아 있지만 클라이언트는 그것을 세션 종료로 읽는다. Redis 가 단일 스레드라
     * 두 연산을 한 스크립트에 넣으면 그 창이 사라진다 (S15P21A604-764, GitLab #198).
     *
     * <p><b>계보 키는 구형 2칸 값일 때만, 그것도 {@code NX} 로 쓴다.</b> 구형 값
     * ({@code userId:sessionId})에는 계보가 없어 그 자리가 계보의 시작이고, 그것을 안 만들면 진 쪽이
     * 계보 대조에서 떨어져 유예를 못 받는다. 반대로 현행 3칸 값에도 쓰면, 부분 실패로 남은 고아
     * refresh 키가 현재 계보를 <b>과거 것으로 되돌릴 수</b> 있다 — {@code issue()} 의 네 SET 은
     * 원자적이지 않다. {@code NX} 는 예상 밖의 기존 계보까지 보호한다.
     *
     * <p>3칸도 2칸도 아닌 값은 <b>소비만 하고 표식을 만들지 않는다.</b> 손상된 값을 소비한 뒤 401 로
     * 끝내는 기존 동작과 같아야 하고, {@code nil} 결합으로 스크립트 오류를 내면 그것이 500 이 된다.
     *
     * <p>해시로 만드는 키 이름이 {@code ARGV} 접두사로 들어가는 것은 {@link #REVOKE_FAMILY} 와 같은
     * 이유·같은 단일 인스턴스 전제다.
     */
    private static final RedisScript<String> CONSUME_AND_MARK = new DefaultRedisScript<>("""
            local stored = redis.call('GETDEL', KEYS[1])
            if not stored then return false end

            local legacy = false
            local userId, _, familyId = stored:match('^([^:]+):([^:]+):([^:]+)$')
            if not userId then
              userId = stored:match('^([^:]+):([^:]+)$')
              familyId = ARGV[2]
              legacy = userId ~= nil
            end
            if not userId then return stored end

            if legacy then
              redis.call('SET', ARGV[1] .. userId, familyId, 'EX', ARGV[4], 'NX')
            end
            redis.call('SET', KEYS[2], 'ROTATED:' .. userId .. ':' .. familyId .. ':' .. ARGV[3],
                       'EX', ARGV[4])
            return stored
            """, String.class);

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
        // 구형 값에는 계보가 없다. 이 갱신이 그 계보의 시작이 된다 — 소비된 토큰도 같은 계보로
        // 적어야 시나리오 7 이 구형 세션에도 그대로 선다. 스크립트와 아래 issue 가 같은 값을 써야
        // 하므로 여기서 한 번 만들어 양쪽에 넘긴다.
        String candidateFamilyId = UUID.randomUUID().toString();
        String stored = redis.execute(CONSUME_AND_MARK, List.of(refreshKey(hash), reusedKey(hash)),
                familyKeyPrefix(), candidateFamilyId, String.valueOf(Instant.now().toEpochMilli()),
                String.valueOf(properties.refreshTokenTtl().toSeconds()));
        if (stored == null) {
            // 표식을 한 번만 읽고 판정과 로그가 같은 값을 본다. 두 번 읽으면 그 사이에 바뀐 값이
            // 로그에 남아 서로 다른 사실을 가리킨다 — 진단을 위해 넣은 줄이 진단을 틀리게 한다.
            String usedValue = redis.opsForValue().get(reusedKey(hash));
            if (revokeReusedFamily(usedValue) == Replay.WITHIN_GRACE) {
                log.warn("refresh 거절 — 회전 직후 재생이다. 세션은 살아 있고 다시 보내면 성공한다: {}",
                        describeUsed(usedValue));
                throw new ApiException(ErrorCode.REFRESH_TOKEN_ROTATED);
            }
            // 표식이 없다는 것은 이 해시가 발급된 적이 없거나, 있었는데 저장소에서 사라졌다는
            // 뜻이다. 후자가 #211 의 유력한 후보이고, 그때는 같은 시각에 여러 사람이 이 줄을 낸다.
            log.warn("refresh 거절 — refresh 키가 없다. 재사용 표식={}", describeUsed(usedValue));
            throw new InvalidRefreshTokenException();
        }
        Session session = Session.parse(stored);
        if (session == null) {
            log.warn("refresh 거절 — 저장된 세션 값을 해석할 수 없다. 우리가 쓴 값이므로 정상 경로가 아니다");
            throw new InvalidRefreshTokenException();
        }
        SessionCheck check = check(session.userId(), session.sessionId());
        if (check != SessionCheck.ACTIVE) {
            log.warn("refresh 거절 — 토큰은 살아 있는데 세션이 없다: userId={} 사유={}", session.userId(), check);
            throw new InvalidRefreshTokenException();
        }
        String familyId = session.familyId() != null ? session.familyId() : candidateFamilyId;
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

    /**
     * 이 Access Token 이 가리키는 세션이 아직 그 사람의 현행 세션인가.
     *
     * <p><b>거절을 두 갈래로 가른다.</b> 저장소에 세션 키 자체가 없는 것과, 키는 있는데 다른
     * {@code sessionId} 인 것은 원인이 완전히 다르다 — 앞은 세션이 사라진 것이고(로그아웃·탈퇴·
     * 저장소 소실), 뒤는 어딘가에서 새로 로그인해 밀려난 것이다. 하나의 {@code boolean} 으로 접으면
     * 거절 로그를 봐도 둘을 구분할 수 없다 (GitLab #211).
     *
     * <p>읽기는 한 번 그대로다. 갈래는 그 한 번의 결과로 정한다.
     */
    public SessionCheck check(Long userId, String sessionId) {
        String current = redis.opsForValue().get(sessionKey(userId));
        if (current == null) {
            return SessionCheck.NO_SESSION;
        }
        return current.equals(sessionId) ? SessionCheck.ACTIVE : SessionCheck.SID_MISMATCH;
    }

    /** {@link #check} 의 결과. 거절 로그가 이 이름을 그대로 적는다. */
    public enum SessionCheck {
        /** 현행 세션이다. 통과시킨다. */
        ACTIVE,
        /** 세션 키가 없다 — 폐기됐거나 저장소에서 사라졌다. 같은 시각 여러 사람이면 후자다. */
        NO_SESSION,
        /** 세션 키는 있는데 다른 것이다 — 새 로그인에 밀려난 옛 Access Token 이다. */
        SID_MISMATCH
    }

    /**
     * 폐기된 토큰이 다시 온 자리. 끊을지 말지는 <b>사유와 계보</b>가 정한다.
     *
     * <p>{@code SUPERSEDED} 는 이미 밀려난 토큰이라 지금 세션과 무관하고, 구형 값은 사유도 계보도
     * 없어 판단 근거가 없다. 둘 다 호출자가 던지는 401 로 끝난다.
     */
    private Replay revokeReusedFamily(String usedValue) {
        if (usedValue == null) {
            return Replay.UNKNOWN;
        }
        Used used = Used.parse(usedValue);
        if (used == null || !ROTATED.equals(used.reason())) {
            return Replay.UNKNOWN;
        }
        if (withinGrace(used) && familyIsCurrent(used)) {
            return Replay.WITHIN_GRACE;
        }
        redis.execute(REVOKE_FAMILY,
                List.of(familyKey(used.userId()), activeKey(used.userId()), sessionKey(used.userId())),
                used.familyId(), refreshKeyPrefix(), reusedKeyPrefix(),
                SUPERSEDED + ":" + used.userId() + ":" + used.familyId(),
                String.valueOf(properties.refreshTokenTtl().toSeconds()));
        return Replay.NOT_WITHIN_GRACE;
    }

    /**
     * 회전 직후의 재생인가.
     *
     * <p>상한만 보면 손상된 <b>미래 시각이 영원히 유예를 통과한다.</b> 두 방향을 다 본다. 회전 시각이
     * 없는 구형 표식은 판단 근거가 없으므로 유예가 아니다 — 기존 폐기 판정으로 내려간다.
     */
    private boolean withinGrace(Used used) {
        if (used.rotatedAt() == null) {
            return false;
        }
        Duration age = Duration.between(used.rotatedAt(), Instant.now());
        return !age.isNegative() && age.compareTo(properties.refreshReuseGrace()) <= 0;
    }

    /**
     * 그 계보가 아직 현행인가 — 유예의 네 번째 조건이다.
     *
     * <p>빼면 <b>이미 끝난 계보에도 "재시도하면 된다" 는 코드가 나간다.</b> 계보가 다르면 아래
     * {@code REVOKE_FAMILY} 로 내려가는데, 그 대조도 실패해 아무것도 지우지 않는다 — 지금과 같은
     * 결과에 오류 코드만 제자리를 찾는다.
     *
     * <p>이 읽기와 응답 사이에 계보가 닫히면 재시도 가능 코드를 받은 쪽의 재시도가 일반 401 로
     * 끝난다. <b>폐기를 건너뛰는 판단이 아니라 오류 코드 선택에만 쓰므로</b> 보안 경계는 넓어지지
     * 않는다.
     */
    private boolean familyIsCurrent(Used used) {
        return used.familyId().equals(redis.opsForValue().get(familyKey(used.userId())));
    }

    /** 재사용 판정의 결과. 호출자는 이것으로 오류 코드를 고른다. */
    private enum Replay {
        /** 표식이 없거나, 사유를 알 수 없거나, {@code SUPERSEDED} 다. 현재 세션과 무관하다. */
        UNKNOWN,
        /** 회전 소비분의 재생인데 유예 밖이거나 끝난 계보다. 계보 폐기 판정을 거쳤다. */
        NOT_WITHIN_GRACE,
        /** 회전 직후·현행 계보의 재생. 끊지 않는다 — 한 번 더 보내면 갱신된 쿠키로 성공한다. */
        WITHIN_GRACE
    }

    private String activeKey(Long userId) { return keyspace + "auth:active:" + userId; }
    private String refreshKey(String hash) { return refreshKeyPrefix() + hash; }
    private String reusedKey(String hash) { return reusedKeyPrefix() + hash; }
    private String sessionKey(Long userId) { return keyspace + "auth:session:" + userId; }
    private String familyKey(Long userId) { return familyKeyPrefix() + userId; }
    private String familyKeyPrefix() { return keyspace + "auth:family:"; }
    private String refreshKeyPrefix() { return keyspace + "auth:refresh:"; }
    private String reusedKeyPrefix() { return keyspace + "auth:refresh:used:"; }

    /**
     * 재사용 표식을 로그 한 조각으로 만든다.
     *
     * <p>적는 것은 사유와 주체까지다 — 해시는 Refresh Token 에서 바로 나오는 값이라 로그에 남기지
     * 않고, 계보 식별자는 사람이 읽을 때 쓸모가 없어 뺀다 (헌법 13조).
     */
    private static String describeUsed(String usedValue) {
        if (usedValue == null) {
            return "없음";
        }
        Used used = Used.parse(usedValue);
        return used == null ? "구형·손상" : used.reason() + " userId=" + used.userId();
    }

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
    private record Used(String reason, Long userId, String familyId, Instant rotatedAt) {

        /**
         * 구형 3칸({@code REASON:userId:familyId})과 현행 4칸(뒤에 회전 시각 epoch millis)을 모두 받는다.
         *
         * <p>한계를 {@code -1} 로 두는 것은 후행 빈 칸까지 남기기 위해서다 —
         * {@code markSuperseded} 가 계보를 모를 때 쓰는 {@code SUPERSEDED:12:} 가 길이 3 으로 정확히
         * 잡혀야 한다. 양수 한계는 그 처리를 놓고 헷갈릴 여지가 있다.
         *
         * <p><b>회전 시각이 숫자가 아니면 {@code null} 로 접고 {@code Used} 자체는 살린다.</b> 사유와
         * 계보가 남아야 계보 폐기 검사가 계속 돈다 — 파싱 실패로 탐지가 조용히 꺼지면 안 된다.
         */
        static Used parse(String value) {
            String[] fields = value.split(":", -1);
            if (fields.length != 3 && fields.length != 4) {
                return null;
            }
            Long userId = parseUserId(fields[1]);
            if (userId == null) {
                return null;
            }
            Instant rotatedAt = fields.length == 4 ? parseRotatedAt(fields[3]) : null;
            return new Used(fields[0], userId, fields[2], rotatedAt);
        }
    }

    private static Instant parseRotatedAt(String value) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException malformed) {
            return null;
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
