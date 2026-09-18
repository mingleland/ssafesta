package com.example.ssafesta.consultation.ws;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 상담 상태 변화를 구독자에게 알린다 (spec 011, contracts §B).
 *
 * <p>봉투는 {@code {type, requestId, occurredAt, …}} 하나로 고정돼 있고 클라이언트는 {@code type}
 * 으로 갈라 읽는다. {@code occurredAt} 이 있는 이유는 <b>재연결 직후 순서를 가르기 위해서</b>다 —
 * 끊긴 사이의 이벤트는 유실되고, 클라이언트는 REST 로 다시 읽은 상태와 그 뒤에 도착한 이벤트를
 * 이 값으로 정렬한다.
 *
 * <p><b>이 알림은 정본이 아니다.</b> 정본은 REST 이고 이벤트 재전송은 P1 에 없다. 그래서
 * (S15P21A604-693) 두 가지를 코드로 보장한다 — 예전에는 Javadoc 에만 있었다:
 *
 * <ul>
 *   <li><b>커밋 뒤에 나간다.</b> 호출 쪽은 트랜잭션 안에서 부른다. 그 자리에서 바로 보내면
 *       구독자가 REST 로 다시 읽었을 때 아직 없는 상담을 알리게 되고, 롤백된 상담도 알린다.
 *       트랜잭션이 없으면 즉시 보낸다.
 *   <li><b>발행 실패는 WARN 한 줄이다.</b> 여기서 던지는 예외가 호출 쪽으로 올라가면 수락이
 *       롤백돼 "알림이 안 갔다" 가 "상담이 안 열렸다" 가 된다.
 * </ul>
 *
 * <p>봉투의 {@code occurredAt} 은 호출 시점에 찍는다 — 커밋 뒤로 미루면 같은 트랜잭션의 이벤트
 * 순서가 뒤집힐 수 있다.
 */
@Component
public class ConsultationEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ConsultationEventPublisher.class);
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

    /**
     * 상담이 끝났다 — 방문자와 그 부스 직원 양쪽에서 카드를 내린다.
     *
     * <p>직원 토픽이 빠지면 방문자가 먼저 나간 뒤에도 직원 화면에 "진행 중" 이 남고, 그 카드로
     * 무언가를 더 하면 터진다 — {@code taken} 을 미리 넣은 사정과 같다 (GitLab #133, 2026-09-14 FE 확정).
     *
     * <p><b>종료한 직원 본인도 받는다.</b> 개인이 아니라 부스 토픽으로 보내기 때문이고,
     * {@code taken}(수락한 본인도 받는다)이 이미 같은 성질이다.
     */
    public void ended(Long boothId, Long visitorUserId, Long requestId) {
        toStaff(boothId, envelope("ended", requestId));
        toVisitor(visitorUserId, envelope("ended", requestId));
    }

    /** 10분이 지났다 — 방문자와 그 부스 직원 양쪽에서 사라진다. */
    public void expired(Long boothId, Long visitorUserId, Long requestId) {
        toStaff(boothId, envelope("expired", requestId));
        toVisitor(visitorUserId, envelope("expired", requestId));
    }

    private void toStaff(Long boothId, Map<String, Object> event) {
        // (Object) 캐스팅은 오버로드를 가른다 — Map 은 convertAndSend(D, T) 와도 맞아 모호해진다.
        publish(event, () -> messaging.convertAndSend(STAFF_TOPIC.formatted(boothId), (Object) event));
    }

    private void toVisitor(Long visitorUserId, Map<String, Object> event) {
        publish(event, () -> messaging.convertAndSendToUser(String.valueOf(visitorUserId), VISITOR_QUEUE, event));
    }

    /** 트랜잭션 안이면 커밋 뒤에, 아니면 지금. 어느 쪽이든 실패는 로그로 끝난다. */
    private void publish(Map<String, Object> event, Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeSend(event, send);
                }
            });
            return;
        }
        safeSend(event, send);
    }

    private static void safeSend(Map<String, Object> event, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException failure) {
            // 정본은 REST 다. 여기서 던지면 알림 장애가 상담 장애가 된다 — 어느 알림이 빠졌는지만
            // 원인과 함께 남긴다. 구독자는 재연결 시 REST 로 다시 읽는다.
            log.warn("상담 이벤트 발행 실패 — type={} requestId={}",
                    event.get("type"), event.get("requestId"), failure);
        }
    }

    private static Map<String, Object> envelope(String type, Long requestId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("requestId", String.valueOf(requestId));
        event.put("occurredAt", Instant.now().toString());
        return event;
    }
}
