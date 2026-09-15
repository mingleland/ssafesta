package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 한 사람이 얼마나 자주 말할 수 있는가 (S15P21A604-687).
 *
 * <p><b>한 번의 원자 연산이다.</b> {@code SETNX} 와 {@code EXPIRE} 를 따로 부르면 그 사이에
 * 죽었을 때 TTL 없는 키가 남고, 그 사람은 영영 말을 못 하게 된다.
 *
 * <p><b>Redis 가 답하지 않으면 막는다</b>(fail-closed). 도배 방지는 이 기능의 필수 조건이라,
 * 판정할 수 없을 때 열어 두면 Redis 가 흔들리는 순간 광장이 도배된다. 열어 두는 쪽이 사용자
 * 경험은 낫지만 그때 잃는 것이 더 크다.
 *
 * <p>키에 환경 namespace 를 붙인다 — dev 와 demo 가 Redis 하나를 공유한다
 * ({@link RedisKeyspaceProperties}).
 */
@Component
public class WorldChatRateLimiter {

    /** 계약값. 광장 잡담에서 3초는 대화를 막지 않으면서 도배는 못 하게 하는 간격이다. */
    static final Duration INTERVAL = Duration.ofSeconds(3);

    private static final String KEY_PREFIX = "world:chat:rate:";

    private final StringRedisTemplate redis;
    private final String keyspace;

    public WorldChatRateLimiter(StringRedisTemplate redis, RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.keyspace = keyspace.prefix();
    }

    /**
     * 이 회원이 지금 말해도 되는가.
     *
     * @throws WorldChatUnavailableException Redis 가 답하지 않아 판정할 수 없을 때
     */
    public boolean tryAcquire(Long userId) {
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(keyspace + KEY_PREFIX + userId, "1", INTERVAL);
        } catch (RuntimeException unreachable) {
            throw new WorldChatUnavailableException(unreachable);
        }
        if (first == null) {
            // 파이프라인·트랜잭션 모드에서만 null 이다. 그 상태로는 판정이 성립하지 않는다.
            throw new WorldChatUnavailableException(null);
        }
        return first;
    }
}
