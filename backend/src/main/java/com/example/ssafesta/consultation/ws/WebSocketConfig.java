package com.example.ssafesta.consultation.ws;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 상담 알림 채널 (spec 011 C-05·FR-012, contracts §B).
 *
 * <p><b>SockJS 를 등록하지 않는다.</b> C-05 가 native WebSocket 위의 STOMP 로 못박았고, 소비자가
 * 브라우저 하나뿐이라 fallback 계층이 얻는 것이 없다.
 *
 * <p><b>경로가 {@code /api/v1} 아래가 아니다.</b> STOMP 엔드포인트 등록은 REST 매핑과 별개라
 * 확정 계약도 {@code /ws/consultation} 으로 적고 있다.
 *
 * <p><b>상담에는 SEND destination 이 없다.</b> 이 채널은 서버에서 클라이언트로 가는 단방향
 * 알림이고 행동은 전부 REST 다(C-12). 월드 채팅(S15P21A604-687)이 {@code /app} 접두를 열었지만
 * 상담 쪽 destination 은 여전히 하나도 없다.
 *
 * <p><b>이 설정의 부재·존재를 보안 경계로 삼지 않는다.</b> 클라이언트가 보낼 수 있는
 * destination 은 {@link StompAuthChannelInterceptor} 의 allowlist 가 정하며, 그 밖의 SEND 는
 * broker destination 으로 가는 것까지 포함해 전부 거부된다.
 *
 * <p><b>한계를 적어 둔다.</b> in-memory simple broker 라 <b>단일 인스턴스 전제</b>다. Spring 을
 * 두 대 이상 띄우면 A 에 붙은 직원이 B 가 발행한 이벤트를 받지 못한다. P1 배포 형상이 단일
 * 인스턴스라 지금은 충분하고, 스케일아웃이 정해지면 외부 브로커 릴레이가 필요하다 — 그것은
 * 배포 형상 결정 뒤의 후속이지 구현자가 지금 고를 문제가 아니다 (research R-05).
 *
 * <p>유실은 계약이 이미 인정한다 — 이벤트 재전송이 P1 에 없고, 클라이언트는 재연결 직후 대기열과
 * 요청 상태를 REST 로 다시 읽는다. <b>정본은 REST 이고 이것은 알림이다.</b>
 *
 * <p><b>유휴 연결도 실제 트래픽을 낸다.</b> simple broker 는 25초마다 heartbeat 를 보내 Cloudflare
 * 계층이 조용히 연결을 정리하지 않게 한다(S15P21A604-825). FE 가 수신 희망값을 20초로 협상하므로
 * 실제 송신 주기는 {@code max(25초, 20초)}인 25초다.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final ObjectProvider<TaskScheduler> heartbeatScheduler;

    WebSocketConfig(
            StompAuthChannelInterceptor authInterceptor,
            @Qualifier("messageBrokerTaskScheduler") ObjectProvider<TaskScheduler> heartbeatScheduler) {
        this.authInterceptor = authInterceptor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // /ws 가 범용 엔드포인트다. 한 소켓으로 상담 알림과 월드 채팅을 함께 쓴다 — STOMP 는
        // 엔드포인트가 달라도 destination 공간을 공유하므로 둘로 나눠도 격리가 생기지 않는다.
        // /ws/consultation 은 이미 FE 에 통보한 경로라 남겨 둔다.
        registry.addEndpoint("/ws", "/ws/consultation").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 클라이언트가 보낼 수 있는 유일한 접두. 무엇이 실제로 열려 있는지는 인터셉터가 정한다.
        registry.setApplicationDestinationPrefixes("/app");
        // /topic — 부스 대기열(직원 여럿), /queue — 방문자 개인 (/user 접두와 함께 쓴다).
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(heartbeatScheduler.getObject())
                .setHeartbeatValue(new long[] {25_000, 0});
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
