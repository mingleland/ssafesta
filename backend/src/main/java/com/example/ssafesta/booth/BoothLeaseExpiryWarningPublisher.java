package com.example.ssafesta.booth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Sends a committed lease-expiry reminder to its owner's private STOMP queue. */
@Component
class BoothLeaseExpiryWarningPublisher {
    static final String DESTINATION = "/queue/booth-lease-expiry";
    private static final Logger log = LoggerFactory.getLogger(BoothLeaseExpiryWarningPublisher.class);

    private final SimpMessagingTemplate messaging;

    BoothLeaseExpiryWarningPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /**
     * WebSocket is an alert channel, not the lease source of truth. The event leaves only after the
     * claim commits, and a broker failure is logged instead of rolling back that claim.
     */
    void expiring(Long userId, Long leaseId, Long boothId, Instant endsAt, long remainingSeconds) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "lease-expiring");
        event.put("leaseId", String.valueOf(leaseId));
        event.put("boothId", String.valueOf(boothId));
        event.put("endsAt", endsAt.toString());
        event.put("remainingSeconds", remainingSeconds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeSend(userId, event);
                }
            });
            return;
        }
        safeSend(userId, event);
    }

    private void safeSend(Long userId, Map<String, Object> event) {
        try {
            messaging.convertAndSendToUser(String.valueOf(userId), DESTINATION, event);
        } catch (RuntimeException failure) {
            log.warn("임대 만료 사전 알림 발행 실패 — userId={}, leaseId={}", userId, event.get("leaseId"), failure);
        }
    }
}
