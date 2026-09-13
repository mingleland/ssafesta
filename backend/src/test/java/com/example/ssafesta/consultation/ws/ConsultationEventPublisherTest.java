package com.example.ssafesta.consultation.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 이벤트 봉투와 destination 을 고정한다 (spec 011, contracts §B).
 *
 * <p>FE 가 의존하는 것은 정확히 이 두 가지다 — 어디로 오는가(destination)와 어떻게 갈라 읽는가
 * ({@code type}). 둘 중 하나가 조용히 바뀌면 화면은 아무 일도 일어나지 않는 상태가 되고, 서버
 * 로그에는 흔적이 없다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 브로커로 나가는 메시지를 가로채는 채널 하나면 충분하고,
 * 그래야 <b>실제로 나간 destination 문자열</b>을 그대로 볼 수 있다.
 *
 * <p>서비스가 이 메서드들을 부르는지는 여기서 보지 않는다 — 그 배선은
 * {@code ConsultationApiIntegrationTest} 의 각 경로가 지나가며 밟는다.
 */
class ConsultationEventPublisherTest {

    private final List<Message<?>> sent = new ArrayList<>();
    private final ConsultationEventPublisher publisher =
            new ConsultationEventPublisher(new SimpMessagingTemplate(capturingChannel()));

    @Test
    void requestedGoesToTheBoothTopicWithTheVisitorAndSummary() {
        publisher.requested(7L, 901L, "덕", Instant.parse("2026-09-13T05:00:00Z"), null);

        assertEquals("/topic/booths/7/consultation", destinationOf(0));
        Map<?, ?> event = payloadOf(0);
        assertEquals("requested", event.get("type"));
        assertEquals("901", event.get("requestId"), "id 는 문자열이다 — 계약이 string 으로 적는다.");
        assertEquals("덕", event.get("visitorNickname"));
        assertTrue(event.containsKey("handoffSummary"),
                "요약이 없어도 키는 있어야 한다 — 키 부재와 null 을 FE 가 구분하지 않게 한다.");
        assertNull(event.get("handoffSummary"));
        assertTrue(event.get("occurredAt") instanceof String,
                "재연결 직후 순서를 가르는 값이라 항상 실린다.");
    }

    /** 수락은 <b>두 곳</b>으로 간다 — 진 직원들의 카드를 내리고, 방문자에게 알린다. */
    @Test
    void acceptingTellsBothTheQueueAndTheVisitor() {
        publisher.taken(7L, 901L, "상담원");
        publisher.accepted(42L, 901L, "상담원");

        assertEquals("/topic/booths/7/consultation", destinationOf(0));
        assertEquals("taken", payloadOf(0).get("type"));
        assertEquals("상담원", payloadOf(0).get("staffName"));

        assertTrue(destinationOf(1).contains("/queue/consultation"),
                "방문자 개인 큐로 가야 한다. 실제 destination: " + destinationOf(1));
        assertEquals("accepted", payloadOf(1).get("type"));
    }

    @Test
    void cancellingClearsTheCardFromTheQueue() {
        publisher.cancelled(7L, 901L);

        assertEquals("/topic/booths/7/consultation", destinationOf(0));
        assertEquals("cancelled", payloadOf(0).get("type"));
    }

    @Test
    void endingTellsTheVisitor() {
        publisher.ended(42L, 901L);

        assertTrue(destinationOf(0).contains("/queue/consultation"));
        assertEquals("ended", payloadOf(0).get("type"));
    }

    /** 만료는 양쪽에서 사라진다 — 한쪽만 보내면 남은 화면에 죽은 카드가 남는다. */
    @Test
    void expiryReachesBothSides() {
        publisher.expired(7L, 42L, 901L);

        assertEquals(2, sent.size(), "대기열과 방문자 양쪽에 가야 합니다.");
        assertEquals("/topic/booths/7/consultation", destinationOf(0));
        assertTrue(destinationOf(1).contains("/queue/consultation"));
        assertEquals("expired", payloadOf(0).get("type"));
        assertEquals("expired", payloadOf(1).get("type"));
    }

    private MessageChannel capturingChannel() {
        return new MessageChannel() {
            @Override
            public boolean send(Message<?> message, long timeout) {
                sent.add(message);
                return true;
            }
        };
    }

    private String destinationOf(int index) {
        Object destination = sent.get(index).getHeaders()
                .get(SimpMessageHeaderAccessor.DESTINATION_HEADER);
        return String.valueOf(destination);
    }

    private Map<?, ?> payloadOf(int index) {
        return (Map<?, ?>) sent.get(index).getPayload();
    }
}
