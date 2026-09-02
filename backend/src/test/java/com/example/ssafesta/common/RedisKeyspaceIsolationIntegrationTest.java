package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.AccessTokenService;
import com.example.ssafesta.auth.AuthProperties;
import com.example.ssafesta.auth.InvalidRefreshTokenException;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.user.User;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.DailyCoinGrantService;
import com.example.ssafesta.wallet.WalletProperties;
import com.example.ssafesta.wallet.WalletService;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * dev and demo share one Redis instance (infra-002 T059·T060), so what has to be proven is
 * isolation between two namespaces on the <b>same</b> connection. The services are built by hand
 * instead of raising a second application context: a second context would separate them through
 * something other than the namespace and the test would pass even with the prefix removed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisKeyspaceIsolationIntegrationTest {

    private static final RedisKeyspaceProperties ENV_A = new RedisKeyspaceProperties("keyspace-a");
    private static final RedisKeyspaceProperties ENV_B = new RedisKeyspaceProperties("keyspace-b");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private StringRedisTemplate redis;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private AuthProperties authProperties;
    @Autowired private WalletService wallets;
    @Autowired private WalletProperties walletProperties;
    @Autowired private UserRepository users;

    @Test
    void issuingInOneEnvironmentDoesNotRotateAwayTheOthersRefreshToken() {
        MemberSessionService envA = sessionsIn(ENV_A);
        MemberSessionService envB = sessionsIn(ENV_B);
        Long userId = 994_349L;

        MemberSessionService.MemberSession inA = envA.issue(userId);
        // Same user id: a shared `auth:active:{userId}` would mark A's hash reused right here.
        MemberSessionService.MemberSession inB = envB.issue(userId);

        // refresh() throws once the stored hash has been rotated away, so surviving it is the proof.
        envA.refresh(inA.refreshToken());
        envB.refresh(inB.refreshToken());
    }

    @Test
    void revokingInOneEnvironmentLeavesTheOtherSignedIn() {
        MemberSessionService envA = sessionsIn(ENV_A);
        MemberSessionService envB = sessionsIn(ENV_B);
        Long userId = 994_350L;
        MemberSessionService.MemberSession inA = envA.issue(userId);
        MemberSessionService.MemberSession inB = envB.issue(userId);

        envA.revoke(userId);

        assertThrows(InvalidRefreshTokenException.class, () -> envA.refresh(inA.refreshToken()),
                "로그아웃한 환경의 세션은 끊겨야 합니다");
        envB.refresh(inB.refreshToken());
    }

    @Test
    void theDailyGrantCacheKeyIsSeparatePerEnvironment() {
        Long userId = users.save(new User("환경격리w" + SEQUENCE.incrementAndGet())).getId();
        wallets.openWallet(userId);
        DailyCoinGrantService envA = dailyGrantsIn(ENV_A);
        DailyCoinGrantService envB = dailyGrantsIn(ENV_B);
        LocalDate today = envA.currentDate();
        String suffix = "wallet:daily:" + userId + ":" + today;

        assertTrue(envA.grantIfDue(userId, today), "첫 지급은 성공해야 합니다");

        assertEquals(Set.of(ENV_A.prefix() + suffix), redis.keys("*" + suffix),
                "지급한 환경의 키만 있어야 합니다");

        // B's cache misses and falls through to the ledger, which refuses the second grant — the
        // ledger is what keeps the money right, and the namespace is what keeps the caches apart.
        envB.grantIfDue(userId, today);

        assertEquals(Set.of(ENV_A.prefix() + suffix, ENV_B.prefix() + suffix), redis.keys("*" + suffix),
                "같은 사용자·같은 날짜 키가 환경마다 따로 있어야 합니다");
    }

    private MemberSessionService sessionsIn(RedisKeyspaceProperties keyspace) {
        return new MemberSessionService(redis, accessTokens, authProperties, keyspace);
    }

    private DailyCoinGrantService dailyGrantsIn(RedisKeyspaceProperties keyspace) {
        return new DailyCoinGrantService(wallets, redis, walletProperties, keyspace);
    }
}
