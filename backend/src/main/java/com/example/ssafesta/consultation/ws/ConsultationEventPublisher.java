package com.example.ssafesta.consultation.ws;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 상담 상태 변화를 구독자에게 알린다 (spec 011, contracts §B).
 *
 * <p>봉투는 {@code {type, requestId, occurredAt, …}} 하나로 고정돼 있고 클라이언트는 {@code type}
 * 으로 갈라 읽는다. {@code occurredAt} 이 있는 이유는 <b>재연결 직후 순서를 가르기 위해서</b>다 —
 * 끊긴 사이의 이벤트는 유실되고, 클라이언트는 REST 로 다시 읽은 상태와 그 뒤에 도착한 이벤트를
 * 이 값으로 정렬한다.
 *
 * <p><b>이 알림은 정본이 아니다.</b> 정본은 REST 이고 이벤트 재전송은 P1 에 없다. 그래서 발행
 * 실패가 상담 자체를 되돌리지 않는다 — 호출 쪽은 트랜잭션 안에서 부르지만, 여기서 던지는 예외가
 * 수락을 무르게 하면 "알림이 안 갔다" 가 "상담이 안 열렸다" 가 된다.
 */
@Component
public class ConsultationEventPublisher {

    private static final String STAFF_TOPIC = "/topic/booths/%d/consultation";
    private static final String VISITOR_QUEUE = "/queue/consultation";

    private final SimpMessagingTemplate messaging;

    public ConsultationEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** 새 요청이 대기열에 들어왔다 — 그 부스 직원들에게. */
    public void requested(Long boothId, Long requestId, String visitorNickname, Instant requestedAt,
                          String handoffSummary) {
        Map<String, Object> event = envelope("requested", requestId);
        event.put("visitorNickname", visitorNickname);
        event.put("requestedAt", requestedAt.toString());
        event.put("handoffSummary", handoffSummary);
        toStaff(boothId, event);
    }

    /** 방문자가 거뒀다 — 카드를 내린다. */
    public void cancelled(Long boothId, Long requestId) {
        toStaff(boothId, envelope("cancelled", requestId));
    }

    /**
     * 다른 직원이 먼저 가져갔다 — 남은 직원들의 대기열에서 카드를 내린다.
     *
     * <p>이것이 없으면 진 카드가 화면에 남아 있다가 누를 때 409 로 터진다. FE Port 가
     * {@code taken} 을 미리 적어 둔 이유가 그것이다.
     */
    public void taken(Long boothId, Long requestId, String staffNickname) {
        Map<String, Object> event = envelope("taken", requestId);
        event.put("staffName", staffNickname);
        toStaff(boothId, event);
    }

    /** 수락됐다 — 방문자에게. */
    public void accepted(Long visitorUserId, Long requestId, String staffNickname) {
        Map<String, Object> event = envelope("accepted", requestId);
        event.put("staffName", staffNickname);
        toVisitor(visitorUserId, event);
    }

    /** 상담이 끝났다 — 방문자에게. */
    public void ended(Long visitorUserId, Long requestId) {
        toVisitor(visitorUserId, envelope("ended", requestId));
    }

    /** 10분이 지났다 — 방문자와 그 부스 직원 양쪽에서 사라진다. */
    public void expired(Long boothId, Long visitorUserId, Long requestId) {
        toStaff(boothId, envelope("expired", requestId));
        toVisitor(visitorUserId, envelope("expired", requestId));
    }

    private void toStaff(Long boothId, Map<String, Object> event) {
        // (Object) 캐스팅은 오버로드를 가른다 — Map 은 convertAndSend(D, T) 와도 맞아 모호해진다.
        messaging.convertAndSend(STAFF_TOPIC.formatted(boothId), (Object) event);
    }

    private void toVisitor(Long visitorUserId, Map<String, Object> event) {
        messaging.convertAndSendToUser(String.valueOf(visitorUserId), VISITOR_QUEUE, event);
    }

    private static Map<String, Object> envelope(String type, Long requestId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("requestId", String.valueOf(requestId));
        event.put("occurredAt", Instant.now().toString());
        return event;
    }
}
