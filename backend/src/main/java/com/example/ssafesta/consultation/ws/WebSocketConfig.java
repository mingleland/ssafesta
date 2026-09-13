package com.example.ssafesta.consultation.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
 * <p><b>SEND destination 이 없다.</b> P1 에서 이 채널은 서버에서 클라이언트로 가는 단방향
 * 알림이고 행동은 전부 REST 다(C-12) — 그래서 {@code setApplicationDestinationPrefixes} 를
 * 두지 않는다. 다만 이 설정의 부재를 보안 경계로 삼지 않는다. 클라이언트가
 * broker destination 으로 보내는 직접 SEND 는 {@link StompAuthChannelInterceptor} 가 명시적으로
 * 거부한다.
 *
 * <p><b>한계를 적어 둔다.</b> in-memory simple broker 라 <b>단일 인스턴스 전제</b>다. Spring 을
 * 두 대 이상 띄우면 A 에 붙은 직원이 B 가 발행한 이벤트를 받지 못한다. P1 배포 형상이 단일
 * 인스턴스라 지금은 충분하고, 스케일아웃이 정해지면 외부 브로커 릴레이가 필요하다 — 그것은
 * 배포 형상 결정 뒤의 후속이지 구현자가 지금 고를 문제가 아니다 (research R-05).
 *
 * <p>유실은 계약이 이미 인정한다 — 이벤트 재전송이 P1 에 없고, 클라이언트는 재연결 직후 대기열과
 * 요청 상태를 REST 로 다시 읽는다. <b>정본은 REST 이고 이것은 알림이다.</b>
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;

    WebSocketConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/consultation").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic — 부스 대기열(직원 여럿), /queue — 방문자 개인 (/user 접두와 함께 쓴다).
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
