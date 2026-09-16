package com.example.ssafesta.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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

    /**
     * 유예 밖의 재사용은 계보째 끊는다 - spec 001 시나리오 7 은 그대로다 (S15P21A604-660).
     *
     * <p>30초를 기다리지 않는다. 표식의 회전 시각을 과거로 되돌리는 것이 같은 상태이고, 구형 값을
     * 직접 심는 기존 테스트와 같은 방식이다.
     */
    @Test
    void aReplayOutsideTheGraceWindowStillRevokesTheFamily() {
        MemberSessionService.MemberSession first = sessions.issue(991_234L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());
        ageRotationMarker(first.refreshToken(), Duration.ofMinutes(5));

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));
        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(second.refreshToken()),
                "유예 밖 재사용은 계보를 끊어야 한다");
    }

    /**
     * 유예 안의 재사용은 끊지 않는다 - 탭을 하나 더 연 것이지 도난이 아니다 (S15P21A604-764).
     *
     * <p>진 쪽은 401 을 받되 {@code REFRESH_TOKEN_ROTATED} 다. 쿠키는 이미 새 값으로 교체돼 있으므로
     * 한 번 더 보내면 성공한다는 뜻이고, 그 구분이 없으면 FE 가 로그인 화면으로 보낸다.
     */
    @Test
    void aReplayInsideTheGraceWindowKeepsTheWinningSession() {
        MemberSessionService.MemberSession first = sessions.issue(991_246L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());

        ApiException refused = assertThrows(ApiException.class, () -> sessions.refresh(first.refreshToken()));

        assertEquals(ErrorCode.REFRESH_TOKEN_ROTATED, refused.errorCode(),
                "유예 안 재사용은 재시도 가능한 코드로 갈라져야 한다");
        assertDoesNotThrow(() -> sessions.refresh(second.refreshToken()),
                "늦게 도착한 두 번째 탭이 방금 발급된 세션을 끊었다");
    }

    /**
     * 회전 시각이 손상돼도 탐지는 계속 돈다.
     *
     * <p>숫자가 아니면 유예 정보가 없는 것으로 접고 {@code Used} 자체를 버리지 않는다 - 버리면
     * 사유와 계보까지 잃어 계보 폐기가 조용히 꺼진다.
     */
    @Test
    void aMarkerWithAnUnparseableRotationTimeStillRevokes() {
        MemberSessionService.MemberSession first = sessions.issue(991_247L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());
        rewriteRotationField(first.refreshToken(), "not-a-number");

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));
        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(second.refreshToken()),
                "회전 시각을 못 읽으면 유예가 아니라 폐기다");
    }

    /** 미래 시각은 상한만 보면 영원히 유예를 통과한다. 두 방향을 다 본다. */
    @Test
    void aMarkerDatedInTheFutureIsNotInsideTheGraceWindow() {
        MemberSessionService.MemberSession first = sessions.issue(991_248L);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());
        ageRotationMarker(first.refreshToken(), Duration.ofHours(-1));

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(first.refreshToken()));
        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(second.refreshToken()),
                "미래 시각이 유예로 읽히면 재사용 탐지가 영영 꺼진다");
    }

    /**
     * 구형 2칸 토큰도 동시 회전에서 두 탭이 함께 죽지 않는다.
     *
     * <p>구형 값에는 계보가 없어 {@code auth:family} 키도 없다. 표식에만 후보 계보를 적으면 진 쪽이
     * 계보 대조에서 떨어져 일반 401 을 받는다 - {@code refresh-token-ttl} 이 {@code P7D} 라 배포 후
     * 7일 동안 구형 토큰이 바로 그 상태가 된다. 소비와 함께 계보 키를 만들어야 닫힌다.
     */
    @Test
    void aLegacyTokenRotatedByTwoTabsAtOnceKeepsTheWinner() throws Exception {
        long userId = 991_249L;
        String legacyToken = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        // 구형 포맷 - 계보 칸이 없다.
        redis.opsForValue().set(key("auth:refresh:" + sha256(legacyToken)), userId + ":" + sessionId, TTL);
        redis.opsForValue().set(key("auth:session:" + userId), sessionId, TTL);
        redis.opsForValue().set(key("auth:active:" + userId), sha256(legacyToken), TTL);

        Outcome outcome = raceTwoRefreshes(legacyToken);

        assertEquals(1, outcome.succeeded(), "같은 토큰으로 두 세션이 발급되면 안 된다");
        assertEquals(List.of(ErrorCode.REFRESH_TOKEN_ROTATED), outcome.refusals(),
                "구형 토큰의 진 쪽도 재시도 가능한 코드를 받아야 한다");
        assertNotNull(redis.opsForValue().get(key("auth:family:" + userId)),
                "구형 회전은 그 자리에서 계보를 만들어야 한다");
        assertDoesNotThrow(() -> sessions.refresh(outcome.winner().refreshToken()),
                "이긴 쪽 세션이 살아 있어야 한다");
    }

    /**
     * 고아 3칸 키가 현재 계보를 과거로 되돌리지 않는다.
     *
     * <p>{@code issue} 의 네 SET 이 원자적이지 않아 옛 계보를 단 3칸 키가 남을 수 있다. 소비 스크립트가
     * 계보를 무조건 쓰면 그 키 하나가 현재 계보를 덮어써 이후 재사용 탐지가 틀린다. 그래서 계보 쓰기는
     * 구형 2칸일 때만, 그것도 {@code NX} 다.
     */
    @Test
    void anOrphanedTokenFromAnOldFamilyDoesNotRewindTheCurrentFamily() {
        long userId = 991_250L;
        MemberSessionService.MemberSession current = sessions.issue(userId);
        String currentFamily = redis.opsForValue().get(key("auth:family:" + userId));

        String orphanToken = UUID.randomUUID().toString();
        redis.opsForValue().set(key("auth:refresh:" + sha256(orphanToken)),
                userId + ":" + UUID.randomUUID() + ":" + UUID.randomUUID(), TTL);

        assertThrows(InvalidRefreshTokenException.class, () -> sessions.refresh(orphanToken));

        assertEquals(currentFamily, redis.opsForValue().get(key("auth:family:" + userId)),
                "고아 키가 현재 계보를 되돌렸다");
        assertDoesNotThrow(() -> sessions.refresh(current.refreshToken()),
                "고아 키 소비가 현재 세션을 끊었다");
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
        // 완전 동시를 실제로 만들어야 표식 공백이 재현된다. invokeAll 만으로는 한쪽이 먼저 끝나는
        // 경우가 섞여, 소비와 표식 기록이 갈라진 구현에서도 초록일 수 있다.
        for (long userId = 991_251L; userId <= 991_255L; userId++) {
            MemberSessionService.MemberSession issued = sessions.issue(userId);

            Outcome outcome = raceTwoRefreshes(issued.refreshToken());

            assertEquals(1, outcome.succeeded(),
                    "같은 토큰으로 두 세션이 발급되면 재사용 감지가 우회된다 - userId=" + userId);
            assertEquals(List.of(ErrorCode.REFRESH_TOKEN_ROTATED), outcome.refusals(),
                    "진 쪽이 일반 401 을 받으면 FE 는 그것을 세션 종료로 읽는다 - userId=" + userId);
        }
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
     * <p><b>한 번짜리 회전 테스트는 이것을 못 잡는다.</b> 회전이 한 번뿐이면 그 회전이 만든 계보가 곧
     * 활성 계보라서 대조가 그대로 통과하기 때문이다. 회전마다 계보를 새로 발급하도록 변이시켜
     * 확인했고, 그때 빨개진 것은 이 테스트 하나뿐이었다 — 기존
     * {@code reusedRotatedRefreshTokenRevokesTheActiveSession} 은 초록으로 남았다.
     *
     * <p>회전 표식을 유예 밖으로 밀어 두고 본다 (S15P21A604-764). 이 테스트가 묻는 것은 <b>계보가
     * 회전을 넘어 이어지는가</b>이지 유예 창이 아니다. 밀어 두지 않으면 방금 회전한 토큰이라
     * 유예에 걸려, 계보를 새로 발급하는 변이를 넣어도 초록이 된다 — 이 테스트가 존재하는 이유가
     * 그 변이를 잡는 것이다.
     */
    @Test
    void aFamilySurvivesRepeatedRotationSoTheFirstTokenStillClosesIt() {
        long userId = 991_238L;
        MemberSessionService.MemberSession first = sessions.issue(userId);
        MemberSessionService.MemberSession second = sessions.refresh(first.refreshToken());
        MemberSessionService.MemberSession third = sessions.refresh(second.refreshToken());
        ageRotationMarker(first.refreshToken(), Duration.ofMinutes(5));

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
     * 세션 거절이 두 갈래로 갈린다 (S15P21A604-816, GitLab #211).
     *
     * <p>{@code boolean} 하나로 접으면 거절 로그를 봐도 <b>세션이 사라진 것</b>과 <b>새 로그인에
     * 밀려난 것</b>을 구분할 수 없다. 앞은 저장소가 비워졌을 때 여러 사람에게 동시에 나고, 뒤는 한
     * 사람에게만 난다 — 원인 추적에서 이 차이가 전부다.
     *
     * <p>거절 여부 자체는 바뀌지 않아야 한다. {@code ACTIVE} 가 아닌 두 갈래 모두 호출자가 401 을
     * 내는 것은 그대로다.
     */
    @Test
    void aSessionRefusalTellsAMissingSessionApartFromASupersededOne() {
        long userId = 991_260L;
        MemberSessionService.MemberSession session = sessions.issue(userId);
        String sessionId = redis.opsForValue().get(key("auth:session:" + userId));

        assertEquals(MemberSessionService.SessionCheck.ACTIVE, sessions.check(userId, sessionId));

        // 새 로그인에 밀려난 옛 Access Token — 세션 키는 있고 값이 다르다.
        sessions.issue(userId);
        assertEquals(MemberSessionService.SessionCheck.SID_MISMATCH, sessions.check(userId, sessionId));

        // 세션 키가 통째로 사라진 자리 — 로그아웃·탈퇴, 그리고 저장소 소실이 여기로 온다.
        redis.delete(key("auth:session:" + userId));
        assertEquals(MemberSessionService.SessionCheck.NO_SESSION, sessions.check(userId, sessionId));

        // sid 클레임이 없는 토큰도 통과시키지 않는다 — 세션 키가 살아 있어도 마찬가지다.
        MemberSessionService.MemberSession revived = sessions.issue(userId);
        assertNotNull(revived.accessToken());
        assertEquals(MemberSessionService.SessionCheck.SID_MISMATCH, sessions.check(userId, null));
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
                        } catch (ApiException refused) {
                            // 재사용은 언제나 거부된다 - 관심사는 그 부수효과다. 경합 결과에 따라
                            // 코드가 갈린다: 계보가 이미 닫혔으면 INVALID_MEMBER_TOKEN, 회전 직후면
                            // REFRESH_TOKEN_ROTATED 다. 둘 다 거부이므로 코드만 확인한다.
                            assertTrue(refused.errorCode() == ErrorCode.INVALID_MEMBER_TOKEN
                                            || refused.errorCode() == ErrorCode.REFRESH_TOKEN_ROTATED,
                                    "예상 밖의 거부 코드: " + refused.errorCode());
                            return null;
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

    /** 두 요청의 시작을 맞춰 완전 동시를 만든다. */
    private Outcome raceTwoRefreshes(String token) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Callable<MemberSessionService.MemberSession>> attempts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            attempts.add(() -> {
                start.await();
                return sessions.refresh(token);
            });
        }
        int succeeded = 0;
        MemberSessionService.MemberSession winner = null;
        List<ErrorCode> refusals = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<MemberSessionService.MemberSession> attempt : pool.invokeAll(attempts)) {
                try {
                    winner = attempt.get();
                    succeeded++;
                } catch (Exception refused) {
                    Throwable cause = refused.getCause() != null ? refused.getCause() : refused;
                    refusals.add(((ApiException) cause).errorCode());
                }
            }
        }
        return new Outcome(succeeded, winner, refusals);
    }

    private record Outcome(int succeeded, MemberSessionService.MemberSession winner,
                           List<ErrorCode> refusals) {
    }

    /** 유예 밖(또는 미래)을 만든다 - 실제로 기다리지 않고 표식의 회전 시각만 옮긴다. */
    private void ageRotationMarker(String rawToken, Duration age) {
        rewriteRotationField(rawToken, String.valueOf(Instant.now().minus(age).toEpochMilli()));
    }

    private void rewriteRotationField(String rawToken, String value) {
        String markerKey = key("auth:refresh:used:" + sha256(rawToken));
        String[] fields = redis.opsForValue().get(markerKey).split(":", -1);
        assertEquals(4, fields.length, "회전 표식에 시각 칸이 없다: " + String.join(":", fields));
        fields[3] = value;
        redis.opsForValue().set(markerKey, String.join(":", fields), TTL);
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
