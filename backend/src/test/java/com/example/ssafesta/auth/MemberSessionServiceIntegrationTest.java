package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MemberSessionServiceIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    private MemberSessionService sessions;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisKeyspaceProperties keyspace;

    @Test
    void reusedRotatedRefreshTokenRevokesTheActiveSession() {
        MemberSessionService.MemberSession first = sessions.issue(991_234L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));
        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(second.refreshToken()));
    }

    /**
     * 같은 refresh 토큰으로 동시에 들어온 두 요청 중 하나만 발급받는다 (S15P21A604-485).
     *
     * <p>읽기와 삭제가 갈라져 있으면 둘 다 세션을 읽고 검사를 통과해 각각 발급한다 — 위 테스트가
     * 잡는 순차 재사용과 달리, 재사용 감지가 켜지지도 않고 지나간다. 소비가 원자적이면 키를 집은
     * 쪽만 살아남고 나머지는 없는 토큰을 본다.
     */
    @Test
    void twoSimultaneousRefreshesWithOneTokenIssueOnce() throws Exception {
        MemberSessionService.MemberSession issued = sessions.issue(991_235L);
        String token = issued.refreshToken();

        List<Callable<MemberSessionService.MemberSession>> attempts = new ArrayList<>();
        attempts.add(() -> sessions.refresh(token));
        attempts.add(() -> sessions.refresh(token));

        int succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<MemberSessionService.MemberSession> attempt : pool.invokeAll(attempts)) {
                try {
                    attempt.get();
                    succeeded++;
                } catch (Exception refused) {
                    // 진 쪽은 GETDEL 이 null 을 준다 — 이 분기가 곧 재사용 판정이다.
                }
            }
        }

        assertEquals(1, succeeded, "같은 토큰으로 두 세션이 발급되면 재사용 감지가 우회된다");
    }

    /**
     * 새 로그인에 밀려난 토큰을 재사용해도 새 세션은 살아 있다 (S15P21A604-660).
     *
     * <p>spec 001 시나리오 4 가 "B의 새 세션만 이용할 수 있다" 를 보장한다. A 가 옛 토큰을 한 번
     * 보내는 것만으로 B 가 끌려 내려가면 그 보장이 깨진다.
     */
    @Test
    void reusingATokenSupersededByANewLoginKeepsTheNewSession() {
        long userId = 991_236L;
        MemberSessionService.MemberSession onDeviceA = sessions.issue(userId);
        MemberSessionService.MemberSession onDeviceB = sessions.issue(userId);

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(onDeviceA.refreshToken()));

        assertDoesNotThrow(() -> sessions.refresh(onDeviceB.refreshToken()),
                "새 로그인에 밀려난 토큰의 재사용이 B 세션까지 끊었다");
    }

    /**
     * 끝난 계보의 회전 소비분은 현재 세션을 건드리지 않는다 (S15P21A604-660).
     *
     * <p>폐기 사유만 보면 A 의 최초 토큰도 "회전 소비분" 이라 B 를 죽인다. 계보를 함께 봐야 그것이
     * 이미 끝난 계보의 토큰임을 알 수 있다 — 이 테스트가 사유 하나만으로 고친 구현을 거른다.
     */
    @Test
    void reusingARotatedTokenFromAClosedFamilyKeepsTheNewSession() {
        long userId = 991_237L;
        MemberSessionService.MemberSession first = sessions.issue(userId);
        sessions.refresh(first.refreshToken());
        MemberSessionService.MemberSession onDeviceB = sessions.issue(userId);

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));

        assertDoesNotThrow(() -> sessions.refresh(onDeviceB.refreshToken()),
                "끝난 계보의 회전 소비분이 현재 세션을 끊었다");
    }

    /**
     * 계보는 회전을 여러 번 지나도 이어진다 (S15P21A604-660).
     *
     * <p>한 번짜리 회전 테스트로도 "회전마다 새 계보를 발급하는" 구현은 걸린다 — 활성 계보가 달라져
     * 차단을 기대하는 단정이 깨지기 때문이다. 여러 홉을 지나며 계보가 흘러내리는 구현은 이 테스트로만
     * 잡힌다.
     */
    @Test
    void aFamilySurvivesRepeatedRotationSoTheFirstTokenStillClosesIt() {
        long userId = 991_238L;
        MemberSessionService.MemberSession first = sessions.issue(userId);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());
        MemberSessionService.MemberSession third = sessions.refresh(second.refreshToken());

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(third.refreshToken()),
                "같은 계보의 최초 토큰이 재사용됐는데 그 계보가 끊기지 않았다");
    }

    /**
     * 배포 시점에 남아 있는 구형 {@code auth:refresh} 값은 살아 있는 토큰이다 (S15P21A604-660).
     *
     * <p>{@code refresh-token-ttl} 이 7일이라 배포 뒤 한동안 {@code userId:sessionId} 두 칸짜리 값이
     * 남는다. 새 포맷을 무조건 기대하면 그 사이 전 회원의 갱신이 실패한다.
     */
    @Test
    void aLegacyRefreshValueRefreshesOnceAndIsUpgradedToTheNewFormat() {
        long userId = 991_239L;
        String rawToken = UUID.randomUUID().toString();
        String hash = sha256(rawToken);
        String sessionId = UUID.randomUUID().toString();

        // 구형 포맷 — 계보 칸이 없다.
        redis.opsForValue().set(key("auth:refresh:" + hash), userId + ":" + sessionId, TTL);
        redis.opsForValue().set(key("auth:active:" + userId), hash, TTL);
        redis.opsForValue().set(key("auth:session:" + userId), sessionId, TTL);
        redis.delete(key("auth:family:" + userId));

        assertDoesNotThrow(() -> sessions.refresh(rawToken), "구형 refresh 값으로 갱신이 실패했다");

        String upgradedHash = redis.opsForValue().get(key("auth:active:" + userId));
        assertNotNull(upgradedHash);
        String upgraded = redis.opsForValue().get(key("auth:refresh:" + upgradedHash));
        assertNotNull(upgraded);
        assertEquals(3, upgraded.split(":", 3).length, "갱신 뒤에도 계보 칸이 붙지 않았다");
        assertNotNull(redis.opsForValue().get(key("auth:family:" + userId)), "계보 키가 초기화되지 않았다");
    }

    /**
     * 구형 {@code auth:refresh:used} 값은 사유도 계보도 없다 — 401 만 내고 세션은 둔다 (S15P21A604-660).
     *
     * <p>"알 수 없으니 차단" 으로 두면 배포 직후 며칠 동안 이 결함이 그대로 재현된다. 모르는 것은
     * 차단의 근거가 아니다.
     */
    @Test
    void aLegacyUsedValueRefusesTheTokenButKeepsTheCurrentSession() {
        long userId = 991_240L;
        MemberSessionService.MemberSession current = sessions.issue(userId);

        String staleToken = UUID.randomUUID().toString();
        // 구형 포맷 — userId 한 칸뿐이다.
        redis.opsForValue().set(key("auth:refresh:used:" + sha256(staleToken)), String.valueOf(userId), TTL);

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(staleToken));

        assertDoesNotThrow(() -> sessions.refresh(current.refreshToken()),
                "구형 used 값의 재사용이 현재 세션을 끊었다");
    }

    /**
     * 재사용 판정과 새 로그인이 겹쳐도 새 세션이 남는다 (S15P21A604-660).
     *
     * <p>계보 대조와 삭제가 갈라져 있으면 대조를 통과한 직후 도착한 로그인의 키를 뒤이은 삭제가
     * 지운다. 둘이 한 원자 연산이고 {@code issue} 가 계보를 가장 먼저 쓰므로, 두 순서 중 무엇이
     * 먼저 오든 마지막에 발급된 세션은 살아 있어야 한다.
     */
    @Test
    void aReuseRacingANewLoginNeverTakesTheNewSessionDown() throws Exception {
        for (long userId = 991_241L; userId <= 991_245L; userId++) {
            MemberSessionService.MemberSession first = sessions.issue(userId);
            sessions.refresh(first.refreshToken());

            long id = userId;
            List<Callable<MemberSessionService.MemberSession>> attempts = List.of(
                    () -> {
                        try {
                            return sessions.refresh(first.refreshToken());
                        } catch (InvalidRefreshTokenException refused) {
                            return null; // 재사용은 언제나 거부된다 — 관심사는 그 부수효과다
                        }
                    },
                    () -> sessions.issue(id));

            MemberSessionService.MemberSession fresh = null;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Future<MemberSessionService.MemberSession> attempt : pool.invokeAll(attempts)) {
                    MemberSessionService.MemberSession result = attempt.get();
                    if (result != null) {
                        fresh = result;
                    }
                }
            }

            assertNotNull(fresh);
            MemberSessionService.MemberSession issued = fresh;
            assertDoesNotThrow(() -> sessions.refresh(issued.refreshToken()),
                    "재사용 판정이 동시에 들어온 새 로그인의 세션을 끊었다 — userId=" + id);
        }
    }

    private String key(String suffix) {
        return keyspace.prefix() + suffix;
    }

    /** {@code MemberSessionService} 가 쓰는 해시와 같아야 구형 값을 심을 수 있다. */
    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
