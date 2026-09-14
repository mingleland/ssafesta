package com.example.ssafesta.consultation.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

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

    // ── 트랜잭션 경계와 실패 (S15P21A604-693) ──────────────────────────────
    //
    // 정본은 REST 다. 알림은 커밋 뒤에만 나가야 하고(구독자가 REST 로 다시 읽으면 보여야 한다),
    // 롤백된 상담을 알려서도, 발행 실패가 상담을 되돌려서도 안 된다.

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 커밋 전에는 0건, 커밋 뒤에 1건 — catch 만 넣은 구현은 커밋 전에 이미 1건이라 여기서 떨어진다. */
    @Test
    void insideATransactionNothingGoesOutUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.cancelled(7L, 901L);
        assertEquals(0, sent.size(), "커밋 전에 나가면 구독자가 REST 로 다시 읽어도 아직 없다.");

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertEquals(1, sent.size());
        assertEquals("cancelled", payloadOf(0).get("type"));
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.cancelled(7L, 901L);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(0, sent.size(), "되돌린 상담을 알리면 화면에 없는 카드가 뜬다.");
    }

    /** 발행 실패는 WARN 한 줄로 끝난다 — 호출한 서비스 트랜잭션까지 전파되면 알림 장애가 상담 장애가 된다. */
    @Test
    void aFailingBrokerIsLoggedWithItsCauseAndNotRethrown() {
        ConsultationEventPublisher failing = new ConsultationEventPublisher(
                new SimpMessagingTemplate((message, timeout) -> {
                    throw new MessageDeliveryException(message, "브로커 없음");
                }));
        Logger logger = (Logger) LoggerFactory.getLogger(ConsultationEventPublisher.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertDoesNotThrow(() -> failing.cancelled(7L, 901L));
        } finally {
            logger.detachAppender(capture);
        }

        List<ILoggingEvent> warnings = capture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), "실패는 정확히 한 번, WARN 으로 남는다.");
        assertNotNull(warnings.getFirst().getThrowableProxy(), "원인 없는 경고는 다음 사람이 추적할 수 없다.");
        assertTrue(warnings.getFirst().getFormattedMessage().contains("901"),
                "어느 요청의 알림이 빠졌는지 requestId 가 있어야 한다.");
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
