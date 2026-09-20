package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 한 사람이 얼마나 자주 말할 수 있는가 (S15P21A604-687, 재설계 S15P21A604-891 / GitLab #223).
 *
 * <h2>고정 간격에서 Burst 허용 + 지속 한도로</h2>
 *
 * <p>처음 구현은 3초 고정 간격이었다. 그 값은 <b>양쪽으로 틀렸다</b> — 정상 대화가 3초에 한 줄로
 * 묶여 광장 잡담이 성립하지 않고, 반대로 3초를 지키는 매크로는 그대로 통과한다. 짧은 연속 입력은
 * 사람의 대화이고, 막아야 하는 것은 <b>지속적인</b> 전송이다.
 *
 * <ul>
 *   <li>최소 간격 {@value #MIN_INTERVAL_MS}ms — 같은 사람의 두 줄 사이 최소 간격
 *   <li>10초 안 {@value #SHORT_WINDOW_MAX}회 · 1분 안 {@value #LONG_WINDOW_MAX}회 — 지속 전송 한도
 *   <li>초과하면 벌칙 대기: 5초 → 10초 → 30초(상한). 서버가 대기 시간을 지정하고 클라이언트는 그
 *       값을 따른다 ({@code retryAfterMs})
 * </ul>
 *
 * <p><b>벌칙 중 재요청은 단계를 올리지 않는다.</b> 남은 대기 시간만 돌려준다 — 올리면 UI 재시도
 * 타이밍을 한 번 어긋낸 것만으로 5초에서 30초까지 순식간에 오른다. 단계는 벌칙이 끝난 뒤 다시
 * 창을 넘겼을 때만 오르고, 마지막 벌칙 종료 후 {@value #STAGE_RESET_MS}ms 동안 초과가 없으면
 * 0으로 돌아간다. <b>정상 메시지 한 줄로는 초기화하지 않는다</b> — 그러면 "대기 → 한 줄 → 대기"
 * 매크로가 영구히 첫 단계에 머물며 통과한다.
 *
 * <h2>왜 Lua 한 덩어리인가</h2>
 *
 * <p>세 창 판정과 벌칙 상태 갱신을 왕복으로 쪼개면 동시에 도착한 전송이 서로의 기록을 보기 전에
 * 둘 다 통과한다 — 창이 새어 나가는 것이 바로 막으려던 도배다. Redis 가 단일 스레드라 한 스크립트에
 * 넣으면 그 창이 사라진다 ({@code MemberSessionService} 가 같은 이유로 같은 방식을 쓴다).
 *
 * <p><b>Redis 가 답하지 않으면 막는다</b>(fail-closed). 도배 방지는 이 기능의 필수 조건이라,
 * 판정할 수 없을 때 열어 두면 Redis 가 흔들리는 순간 광장이 도배된다.
 *
 * <p>키에 환경 namespace 를 붙이고 <b>둘 다 만료</b>시킨다 — 남기면 채팅을 쓴 사람 수만큼 상태가
 * 쌓인다 ({@link RedisKeyspaceProperties}).
 */
@Component
public class WorldChatRateLimiter {

    /** 같은 사람의 두 줄 사이 최소 간격. 사람의 연타는 여기까지 허용한다. */
    static final long MIN_INTERVAL_MS = 800L;

    static final long SHORT_WINDOW_MS = 10_000L;
    static final int SHORT_WINDOW_MAX = 5;
    static final long LONG_WINDOW_MS = 60_000L;
    static final int LONG_WINDOW_MAX = 20;

    /** 초과 횟수별 대기. 마지막 값이 상한이고, 그 뒤로는 계속 그 값이다. */
    static final long[] PENALTY_MS = {5_000L, 10_000L, 30_000L};

    /** 벌칙이 끝난 뒤 이만큼 초과가 없으면 단계가 0으로 돌아간다. */
    static final long STAGE_RESET_MS = 60_000L;

    /** Redis 가 판정을 못 줄 때 내려보내는 대기 시간. 기존 거절 동작을 그대로 두는 값이다. */
    static final long UNAVAILABLE_RETRY_AFTER_MS = 5_000L;

    /**
     * 같은 사람의 입장 알림을 다시 방송하기까지의 간격 (S15P21A604-915 / GitLab #223 §5-5).
     *
     * <p>입장 알림은 대화가 아니라 사실 통지라, 채팅과 같은 창·벌칙 구조를 둘 이유가 없다. 막아야
     * 하는 것은 <b>연결을 끊고 다시 붙기를 반복해 토픽을 밀어 올리는 것</b> 하나다. 그래서 간격
     * 하나로 끝낸다.
     */
    static final long JOIN_NOTICE_INTERVAL_MS = 60_000L;

    private static final String SENDS_PREFIX = "world:chat:sends:";
    private static final String STATE_PREFIX = "world:chat:rate:";
    private static final String JOIN_PREFIX = "world:chat:join:";

    /**
     * 허용 여부와 남은 대기를 한 번에 판정한다.
     *
     * <p>{@code KEYS[1]} 은 허용된 전송 시각의 ZSET, {@code KEYS[2]} 는 벌칙 상태 해시다. 반환은
     * {@code {허용여부, retryAfterMs}} 두 칸이다.
     *
     * <p><b>벌칙 검사가 가장 먼저다.</b> 그 뒤에 창을 세면 벌칙 중의 재요청이 창 기록을 건드려
     * 대기가 끝난 직후의 정상 전송까지 막는다.
     *
     * <p>단계 초기화는 <b>읽는 시점에</b> 판정한다. 별도 만료 이벤트를 두면 그 사이에 들어온
     * 요청이 옛 단계를 보고, 만료를 기다리는 동안 단계가 실제보다 높게 남는다.
     */
    private static final RedisScript<List> JUDGE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local minInterval = tonumber(ARGV[2])
            local shortWindow = tonumber(ARGV[3])
            local shortMax = tonumber(ARGV[4])
            local longWindow = tonumber(ARGV[5])
            local longMax = tonumber(ARGV[6])
            local stageReset = tonumber(ARGV[7])
            local member = ARGV[8]
            local penalties = {}
            for i = 9, #ARGV do penalties[#penalties + 1] = tonumber(ARGV[i]) end

            local blockedUntil = tonumber(redis.call('HGET', KEYS[2], 'until') or '0')
            local stage = tonumber(redis.call('HGET', KEYS[2], 'stage') or '0')

            if blockedUntil > now then
              return {0, blockedUntil - now}
            end
            if stage > 0 and now - blockedUntil > stageReset then
              stage = 0
            end

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - longWindow)
            local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            local tooSoon = false
            if last[2] then tooSoon = (now - tonumber(last[2])) < minInterval end
            local shortCount = redis.call('ZCOUNT', KEYS[1], now - shortWindow, '+inf')
            local longCount = redis.call('ZCARD', KEYS[1])

            if tooSoon or shortCount >= shortMax or longCount >= longMax then
              stage = math.min(stage + 1, #penalties)
              local wait = penalties[stage]
              redis.call('HSET', KEYS[2], 'stage', stage, 'until', now + wait)
              redis.call('PEXPIRE', KEYS[2], wait + stageReset)
              return {0, wait}
            end

            redis.call('ZADD', KEYS[1], now, member)
            redis.call('PEXPIRE', KEYS[1], longWindow + minInterval)
            if stage == 0 and blockedUntil > 0 then
              redis.call('DEL', KEYS[2])
            end
            return {1, 0}
            """, List.class);

    /**
     * 지금 이 사람의 입장을 알려도 되는가.
     *
     * <p>마지막 방송 시각을 {@code KEYS[1]} 에 두고 간격만 본다. {@code SET NX PX} 한 줄로도
     * 되지만 그러면 판정이 <b>Redis 의 TTL 시계</b>에 묶여, 주입한 시각으로 검증할 수 없다.
     * 값에 시각을 적어 두면 판정은 주입한 시각을 따르고 TTL 은 청소만 맡는다.
     */
    private static final RedisScript<Long> JOIN = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local interval = tonumber(ARGV[2])
            local last = tonumber(redis.call('GET', KEYS[1]) or '0')
            if last > 0 and now - last < interval then
              return 0
            end
            redis.call('SET', KEYS[1], now, 'PX', interval)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyspace;
    private final Clock clock;

    @Autowired
    public WorldChatRateLimiter(StringRedisTemplate redis, RedisKeyspaceProperties keyspace) {
        this(redis, keyspace, Clock.systemUTC());
    }

    /** 시각을 밖에서 넣는 생성자 — 창과 벌칙을 기다리지 않고 검증하려면 필요하다. */
    WorldChatRateLimiter(StringRedisTemplate redis, RedisKeyspaceProperties keyspace, Clock clock) {
        this.redis = redis;
        this.keyspace = keyspace.prefix();
        this.clock = clock;
    }

    /**
     * 이 회원이 지금 말해도 되는가.
     *
     * @throws WorldChatUnavailableException Redis 가 답하지 않아 판정할 수 없을 때
     */
    public Decision tryAcquire(Long userId) {
        long now = clock.millis();
        List<?> verdict;
        try {
            verdict = redis.execute(JUDGE,
                    List.of(keyspace + SENDS_PREFIX + userId, keyspace + STATE_PREFIX + userId),
                    String.valueOf(now), String.valueOf(MIN_INTERVAL_MS), String.valueOf(SHORT_WINDOW_MS),
                    String.valueOf(SHORT_WINDOW_MAX), String.valueOf(LONG_WINDOW_MS),
                    String.valueOf(LONG_WINDOW_MAX), String.valueOf(STAGE_RESET_MS),
                    // 같은 밀리초에 두 줄이 통과하면 score 가 같다. member 가 겹치면 ZADD 가 덮어써
                    // 한 줄로 세어지므로 매번 다른 값을 쓴다.
                    now + ":" + UUID.randomUUID(),
                    String.valueOf(PENALTY_MS[0]), String.valueOf(PENALTY_MS[1]), String.valueOf(PENALTY_MS[2]));
        } catch (RuntimeException unreachable) {
            throw new WorldChatUnavailableException(unreachable);
        }
        if (verdict == null || verdict.size() < 2) {
            // 파이프라인·트랜잭션 모드에서만 null 이다. 그 상태로는 판정이 성립하지 않는다.
            throw new WorldChatUnavailableException(null);
        }
        boolean allowed = ((Number) verdict.get(0)).intValue() == 1;
        return new Decision(allowed, ((Number) verdict.get(1)).longValue());
    }

    /**
     * 이 회원의 입장 알림을 지금 방송해도 되는가 (S15P21A604-915).
     *
     * <p>여기서 거절은 "연결이 잘못됐다" 가 아니라 "방금 알렸다" 다. 부르는 쪽은 방송만 건너뛰고
     * 연결은 그대로 둔다 — 입장 알림에는 클라이언트로 거절을 돌려줄 응답 경로가 없다.
     *
     * @throws WorldChatUnavailableException Redis 가 답하지 않아 판정할 수 없을 때
     */
    public boolean tryAnnounceJoin(Long userId) {
        Long verdict;
        try {
            verdict = redis.execute(JOIN, List.of(keyspace + JOIN_PREFIX + userId),
                    String.valueOf(clock.millis()), String.valueOf(JOIN_NOTICE_INTERVAL_MS));
        } catch (RuntimeException unreachable) {
            throw new WorldChatUnavailableException(unreachable);
        }
        if (verdict == null) {
            // 파이프라인·트랜잭션 모드에서만 null 이다. 그 상태로는 판정이 성립하지 않는다.
            throw new WorldChatUnavailableException(null);
        }
        return verdict == 1L;
    }

    /**
     * 판정 결과.
     *
     * @param allowed      말해도 되는가
     * @param retryAfterMs 거절일 때 이만큼 뒤에 다시 보내면 된다. 허용이면 0 이다. 클라이언트 계산보다
     *                     이 값이 우선한다 — 서버가 벌칙 단계를 알고 클라이언트는 모른다
     */
    public record Decision(boolean allowed, long retryAfterMs) { }
}
