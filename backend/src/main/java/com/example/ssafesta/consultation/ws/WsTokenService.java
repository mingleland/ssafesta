package com.example.ssafesta.consultation.ws;

import com.example.ssafesta.common.RedisKeyspaceProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * STOMP 연결용 단기 토큰 (spec 011 FR-019·FR-020, 헌법 13조).
 *
 * <p><b>Access Token 을 그대로 쓰지 않는다.</b> 헌법 13조가 토큰을 4계층으로 가르는 이유가
 * 여기 있다 — WebSocket 핸드셰이크는 헤더를 다루는 방식이 HTTP 요청과 달라서, 수명이 긴 토큰을
 * 그 경로에 태우면 노출 표면이 넓어진다. 그래서 <b>5분짜리 전용 토큰</b>을 따로 발급한다.
 *
 * <p><b>URL query 로 넘기지 않는다</b>(FR-019). 토큰이 접속 로그·referrer 에 남기 때문이고,
 * 그래서 이 값은 STOMP {@code CONNECT} 프레임의 {@code Authorization} 헤더로만 간다.
 *
 * <p><b>주체는 문자열이다</b>(S15P21A604-727). 회원은 id 의 문자열 표현({@code "7"})이고 게스트는
 * 접속 토큰의 주체({@code "guest:<uuid>"})다. 게스트도 연결할 수 있어야 부스 변경 방송이 게스트
 * 화면까지 닿는다 — 그 전에는 게스트가 세션 내내 낡은 부스를 보고 있었다. <b>숫자인 주체는 곧
 * 회원</b>이고, 회원만 할 수 있는 일(채팅 발신·부스 대기열 구독)은 그 판정을 쓴다.
 *
 * <p>저장소는 Redis 다 — Refresh Token 이 이미 쓰고 있어 새 저장소가 늘지 않는다. TTL 이 곧
 * 만료라 스위퍼도 필요 없다.
 *
 * <p>dev·demo 가 Redis 하나를 공유하므로 키 맨 앞에 환경 namespace 를 붙인다.
 * 그렇지 않으면 한 환경에서 발급한 5분 토큰이 다른 환경의 STOMP 연결도 열어 준다.
 *
 * <p><b>연결 성립 후에는 만료가 연결을 끊지 않는다</b>(FR-020). 이 토큰은 <i>연결을 여는</i>
 * 열쇠이지 세션의 수명이 아니다. 끊긴 뒤 재연결할 때 새로 발급받는다.
 */
@Service
public class WsTokenService {

    /** C-14 — 계약값이다. */
    public static final Duration VALID_FOR = Duration.ofMinutes(5);

    private static final String KEY_PREFIX = "consultation:ws:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final String keyspace;

    public WsTokenService(StringRedisTemplate redis, RedisKeyspaceProperties keyspace) {
        this.redis = redis;
        this.keyspace = keyspace.prefix();
    }

    /**
     * 한 사람에게 5분짜리 연결 열쇠를 준다. 재연결마다 새로 발급받는다.
     *
     * @param subject 회원 id 의 문자열 표현, 또는 게스트 접속 토큰의 주체
     */
    public Issued issue(String subject) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        redis.opsForValue().set(key(token), subject, VALID_FOR);
        return new Issued(token, VALID_FOR.toSeconds());
    }

    /**
     * 토큰이 가리키는 주체. 없거나 만료됐으면 비어 있다.
     *
     * <p><b>한 번 쓰고 지우지 않는다.</b> 끊긴 연결을 클라이언트가 즉시 되잇는 경우가 정상
     * 경로이고, 그때마다 REST 왕복을 강제하면 재연결이 느려진다. 5분이라는 짧은 수명이 재사용
     * 창을 대신 막는다.
     */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(key(token)));
    }

    private String key(String token) {
        return keyspace + KEY_PREFIX + token;
    }

    public record Issued(String token, long expiresInSeconds) { }
}
