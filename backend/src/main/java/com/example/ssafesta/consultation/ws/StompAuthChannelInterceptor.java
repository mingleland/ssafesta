package com.example.ssafesta.consultation.ws;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 연결을 인증하고 클라이언트 inbound destination 경계를 지킨다
 * (spec 011 FR-019, 헌법 13조·16조).
 *
 * <p>검증에 실패하면 <b>예외를 던져 연결을 거부한다.</b> 조용히 통과시키면 인증 없는 연결이
 * {@code /topic/booths/*} 를 구독해 남의 부스 대기열을 읽는다.
 *
 * <p>토큰은 {@code Authorization: Bearer <token>} 헤더로만 받는다. <b>URL query 는 보지 않는다</b> —
 * 그 자리에 실린 토큰은 접속 로그와 referrer 에 남는다(FR-019).
 *
 * <p>검증된 회원 id 를 {@link Principal} 로 심어 {@code /user/queue/...} 가 그 사람에게만 가게
 * 한다. Spring 의 user destination 이 이 이름으로 대상을 고른다.
 *
 * <p><b>클라이언트 SEND 는 allowlist 밖이면 거부다.</b> {@code /topic}·{@code /queue}
 * destination 은 컨트롤러를 거치지 않고 simple broker 로 갈 수 있어 서버 이벤트를 위조한다.
 * 허용은 {@link #ALLOWED_SEND_DESTINATIONS} 가 전부이고, 기능이 늘 때마다 그 티켓이 정확한
 * destination 하나씩만 더한다.
 *
 * <p><b>거부는 연결을 끊는다.</b> 여기서 던진 예외는 ERROR 프레임과 함께 세션을 닫는다. 이것이
 * 의도다 — 허용 목록 밖으로 보내는 쪽은 공격자이거나 고장난 클라이언트이고, 조용히 버리면
 * 후자는 자기가 깨진 줄 모른 채 계속 돈다. 한 소켓이 상담 알림과 채팅을 함께 나르므로 대가가
 * 작지 않다는 것은 알고 있다 — 실제로 정상 클라이언트가 여기 걸리면 버리는 쪽으로 바꾼다.
 *
 * <p><b>raw {@code /queue/**} 구독도 거부한다.</b> 개인 알림은 반드시
 * {@code /user/queue/**} 를 통해 Spring 의 session 변환을 거친다. 토픽별 구독 자격은
 * 이 티켓의 범위가 아니라 기존 동작을 유지한다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    /**
     * 클라이언트가 SEND 할 수 있는 destination 전부 (S15P21A604-687).
     *
     * <p>접두 매칭이 아니라 <b>정확히 일치</b>다. {@code /app/world/} 로 시작하는 것을 전부 열면
     * 나중에 추가되는 핸들러가 이 목록을 손대지 않고 열려 버린다.
     */
    private static final Set<String> ALLOWED_SEND_DESTINATIONS = Set.of("/app/world/chat");

    private final WsTokenService tokens;

    public StompAuthChannelInterceptor(WsTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.SEND.equals(command)
                && !ALLOWED_SEND_DESTINATIONS.contains(accessor.getDestination())) {
            throw new IllegalArgumentException(
                    "클라이언트가 SEND 할 수 있는 destination 이 아닙니다.");
        }
        if (StompCommand.SUBSCRIBE.equals(command) && isRawQueue(accessor.getDestination())) {
            throw new IllegalArgumentException(
                    "개인 큐는 /user/queue/** destination 으로 구독해야 합니다.");
        }
        if (!StompCommand.CONNECT.equals(command)) {
            return message;
        }

        Long userId = tokens.resolve(bearerOf(accessor))
                .orElseThrow(() -> new IllegalArgumentException(
                        "상담 채널 연결에는 유효한 WS Token 이 필요합니다."));
        accessor.setUser(new ConsultationPrincipal(String.valueOf(userId)));
        return message;
    }

    private static boolean isRawQueue(String destination) {
        return "/queue".equals(destination)
                || (destination != null && destination.startsWith("/queue/"));
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
