package com.example.ssafesta.eventshop.ws;

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
 * Tells the admin console a purchase just happened (S15P21A604-836, GitLab #217 4번).
 *
 * <p>Same shape as {@code ConsultationEventPublisher}: sent after commit so a subscriber never
 * hears about a purchase that a concurrent rollback then erased, and a publish failure is a WARN
 * log rather than an exception — a notification going missing must never undo the purchase that
 * already charged the buyer's wallet.
 */
@Component
public class EventShopEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventShopEventPublisher.class);
    private static final String ADMIN_TOPIC = "/topic/admin/event-shop";

    private final SimpMessagingTemplate messaging;

    public EventShopEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** A member just bought a prize — the admin console shows it without a page refresh. */
    public void purchased(Long purchaseId, Long prizeId, String prizeName, String buyerNickname,
                          int quantity, int coinSpent, Instant purchasedAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "purchased");
        event.put("purchaseId", String.valueOf(purchaseId));
        event.put("prizeId", String.valueOf(prizeId));
        event.put("prizeName", prizeName);
        event.put("buyerNickname", buyerNickname);
        event.put("quantity", quantity);
        event.put("coinSpent", coinSpent);
        event.put("purchasedAt", purchasedAt.toString());
        publish(event);
    }

    private void publish(Map<String, Object> event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeSend(event);
                }
            });
            return;
        }
        safeSend(event);
    }

    private void safeSend(Map<String, Object> event) {
        try {
            messaging.convertAndSend(ADMIN_TOPIC, (Object) event);
        } catch (RuntimeException failure) {
            log.warn("이벤트 상점 구매 알림 발행 실패 — purchaseId={}", event.get("purchaseId"), failure);
        }
    }
}
