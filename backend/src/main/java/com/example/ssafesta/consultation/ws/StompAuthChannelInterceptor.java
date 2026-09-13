package com.example.ssafesta.consultation.ws;

import java.security.Principal;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * {@code CONNECT} 프레임의 WS Token 을 검증한다 (spec 011 FR-019, 헌법 13조).
 *
 * <p>검증에 실패하면 <b>예외를 던져 연결을 거부한다.</b> 조용히 통과시키면 인증 없는 연결이
 * {@code /topic/booths/*} 를 구독해 남의 부스 대기열을 읽는다.
 *
 * <p>토큰은 {@code Authorization: Bearer …} 헤더로만 받는다. <b>URL query 는 보지 않는다</b> —
 * 그 자리에 실린 토큰은 접속 로그와 referrer 에 남는다(FR-019).
 *
 * <p>검증된 회원 id 를 {@link Principal} 로 심어 {@code /user/queue/...} 가 그 사람에게만 가게
 * 한다. Spring 의 user destination 이 이 이름으로 대상을 고른다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final WsTokenService tokens;

    public StompAuthChannelInterceptor(WsTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        Long userId = tokens.resolve(bearerOf(accessor))
                .orElseThrow(() -> new IllegalArgumentException(
                        "상담 채널 연결에는 유효한 WS Token 이 필요합니다."));
        accessor.setUser(new ConsultationPrincipal(String.valueOf(userId)));
        return message;
    }

    private static String bearerOf(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value != null && value.startsWith(BEARER) ? value.substring(BEARER.length()) : null;
    }

    /** user destination 이 고르는 이름 — 회원 id 의 문자열 표현이다. */
    record ConsultationPrincipal(String name) implements Principal {

        @Override
        public String getName() {
            return name;
        }
    }
}
