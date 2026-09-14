package com.example.ssafesta.consultation.ws;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.MemberPrincipal;
import com.example.ssafesta.staff.StaffAccessGuard;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
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
 * <p>검증된 주체를 {@link Principal} 로 심어 {@code /user/queue/...} 가 그 사람에게만 가게
 * 한다. Spring 의 user destination 이 이 이름으로 대상을 고른다. <b>주체는 회원 id 문자열이거나
 * 게스트의 {@code guest:<uuid>} 다</b>(S15P21A604-727) — 게스트 연결은 읽기 전용이고, 회원만
 * 할 수 있는 일은 {@link MemberPrincipal#optionalMemberId(Principal)} 로 가른다.
 *
 * <p><b>분기는 {@code SimpMessageType} 으로 한다</b>(S15P21A604-692). {@code StompCommand} 는 와이어
 * 표기일 뿐이고 브로커·핸들러가 실제로 보는 것은 simpType 이다 — command 로 가르면 같은 simpType 을
 * 가진 다른 표기가 정책을 그냥 지나간다. 실제로 {@code STOMP}(= {@code CONNECT})는 토큰 검증을,
 * {@code MESSAGE}(= {@code SEND})는 destination 차단을 우회했다.
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
 * {@code /user/queue/**} 를 통해 Spring 의 session 변환을 거친다.
 *
 * <p><b>부스 토픽 구독은 그 부스의 구성원만 한다</b>(S15P21A604-693). 게스트는 구성원일 수
 * 없으므로 여기서 먼저 걸린다. 신원은 {@code CONNECT} 에서
 * 확정되고, 구성원 여부는 {@code SUBSCRIBE} 에서 boothId 별로 본다 — {@code CONNECT} 시점에는
 * boothId 가 없어 멤버십을 검사할 수 없다. GitLab #133(2026-09-07) 에서 FE 에 약속한 동작인데
 * 구현이 빠져 있었다: WS Token 만 있으면 남의 부스 대기열(방문자 닉네임·AI 대화 요약)을 읽을 수
 * 있었다. 주체가 없는 구독도 거부다 — 판정할 대상이 없는데 통과시키면 게이트가 없는 것과 같다.
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
    /**
     * 서버가 클라이언트에게 쓰는 command (S15P21A604-692).
     *
     * <p>{@code MESSAGE} 가 여기 있는 것이 핵심이다 — 그 command 는 {@code SEND} 와 <b>같은
     * {@code SimpMessageType.MESSAGE}</b> 라, 클라이언트가 그 표기로 보내면 브로커가 그대로
     * 처리한다. simpType 분기만으로도 막히지만, 애초에 올 수 없는 프레임이라 이름으로도 끊는다.
     */
    private static final Set<StompCommand> SERVER_ONLY_COMMANDS = Set.of(
            StompCommand.CONNECTED, StompCommand.MESSAGE, StompCommand.RECEIPT, StompCommand.ERROR);
    /** 직원 대기열 토픽. 계약({@code contracts §B})의 문자열과 정확히 같은 모양만 잡는다. */
    private static final Pattern BOOTH_TOPIC = Pattern.compile("^/topic/booths/(\\d+)/consultation$");

    private final WsTokenService tokens;
    private final StaffAccessGuard staffGuard;

    public StompAuthChannelInterceptor(WsTokenService tokens, StaffAccessGuard staffGuard) {
        this.tokens = tokens;
        this.staffGuard = staffGuard;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command != null && SERVER_ONLY_COMMANDS.contains(command)) {
            // 서버가 클라이언트에게 쓰는 command 다. 인바운드로 오는 것은 정상 경로가 아니다.
            throw new IllegalArgumentException("클라이언트가 보낼 수 있는 프레임이 아닙니다.");
        }
        // 분기는 command 가 아니라 simpType 으로 한다 — 브로커·핸들러가 그 값으로 동작하고,
        // command 로 가르면 같은 simpType 을 가진 다른 표기가 정책을 그냥 지나간다
        // (S15P21A604-692: STOMP=CONNECT, MESSAGE=SEND 가 그렇게 새어 나갔다).
        SimpMessageType type = command == null
                ? SimpMessageHeaderAccessor.getMessageType(message.getHeaders())
                : command.getMessageType();
        if (SimpMessageType.MESSAGE.equals(type)
                && !ALLOWED_SEND_DESTINATIONS.contains(accessor.getDestination())) {
            throw new IllegalArgumentException(
                    "클라이언트가 SEND 할 수 있는 destination 이 아닙니다.");
        }
        if (SimpMessageType.SUBSCRIBE.equals(type)) {
            if (isRawQueue(accessor.getDestination())) {
                throw new IllegalArgumentException(
                        "개인 큐는 /user/queue/** destination 으로 구독해야 합니다.");
            }
            requireBoothMemberIfBoothTopic(accessor);
        }
        if (!SimpMessageType.CONNECT.equals(type)) {
            return message;
        }
        String subject = tokens.resolve(bearerOf(accessor))
                .orElseThrow(() -> new IllegalArgumentException(
                        "실시간 채널 연결에는 유효한 WS Token 이 필요합니다."));
        accessor.setUser(new ConsultationPrincipal(subject));
        return message;
    }

    /**
     * 부스 토픽이면 구독자가 그 부스의 Owner 또는 직원(역할 무관)인지 본다.
     *
     * <p>{@code BoothNotFoundException} 은 {@link ApiException} 의 하위라 한 번에 잡는다 — 없는
     * 부스도, 남의 부스도 결과는 같다: ERROR 프레임과 연결 종료. 이 인터셉터의 다른 거부와 같은
     * 모양이다.
     */
    private void requireBoothMemberIfBoothTopic(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Matcher topic = destination == null ? null : BOOTH_TOPIC.matcher(destination);
        if (topic == null || !topic.matches()) {
            return;
        }
        Principal user = accessor.getUser();
        if (user == null) {
            throw new IllegalArgumentException("부스 토픽 구독에는 인증된 연결이 필요합니다.");
        }
        Long memberId = MemberPrincipal.optionalMemberId(user);
        if (memberId == null) {
            // 게스트다. 그 전에는 Long.valueOf 가 던지는 NumberFormatException 에 기대 우연히 막혔고,
            // 거부 이유가 "권한 없음" 으로 나가 게스트에게 뜻이 통하지 않았다 (S15P21A604-727).
            throw new IllegalArgumentException("부스 토픽 구독은 회원만 할 수 있습니다.");
        }
        try {
            staffGuard.requireBoothMember(Long.valueOf(topic.group(1)), memberId);
        } catch (ApiException refused) {
            throw new IllegalArgumentException("부스 토픽 구독 권한이 없습니다.", refused);
        }
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
