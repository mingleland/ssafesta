package com.example.ssafesta.wallet;

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
 * 코인이 들어온 순간을 본인에게 알린다 (S15P21A604-920).
 *
 * <p>SSE 를 새로 세우지 않고 기존 STOMP 채널을 쓴다 — 인증(WS Token)·heartbeat·FE 클라이언트가
 * 이미 있고, 개인 대상 발행도 {@code /user/queue} 로 상담이 쓰고 있는 길 그대로다.
 *
 * <p><b>이 큐는 저지연 힌트이고 정본은 원장이다</b> (S15P21A604-923). 구독 전에 발행된 지급은
 * 도착하지 않는다 — 가입 지급은 가입 트랜잭션 안에서, 일일 지급은 {@code ws-token} 요청의
 * {@code preHandle} 에서 일어나 둘 다 소켓보다 먼저다. 재전송으로 메우지 않는 이유는 지급 사실이
 * 이미 원장에 남아 있기 때문이고, 회수 경로는 {@code GET /api/v1/wallets/me/transactions} 다.
 * 소비자 순서(구독 → REST → 버퍼 병합)는 {@code docs/16} §8 이 정본이다.
 *
 * <p>{@code ConsultationEventPublisher}·{@code EventShopEventPublisher} 와 같은 모양이다: 커밋
 * 뒤에 보내 롤백된 지급을 알리지 않고, 발행 실패는 WARN 로그로 끝낸다 — 알림이 빠졌다고 이미
 * 확정된 지급을 되돌리면 그쪽이 훨씬 나쁜 사고다. 잔액의 정본은 REST 이고 이것은 알림이다.
 */
@Component
public class CoinEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(CoinEventPublisher.class);
    private static final String OWNER_QUEUE = "/queue/coin";

    private final SimpMessagingTemplate messaging;

    public CoinEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /**
     * 코인이 지급됐다. 새 원장 항목이 기록된 증액에만 부른다 — 멱등키로 걸러진 재요청까지
     * 알리면 같은 지급이 두 번 뜬다.
     *
     * <p><b>무엇 때문에 받았는지는 {@code reasonType} 이 가른다</b> — 가입 최초 지급
     * ({@code INITIAL_GRANT}), 일일 접속({@code DAILY_GRANT}), 미션 달성
     * ({@code DAILY_MISSION}), 설문·미니게임·슬롯이 전부 다른 값이다. 클라이언트가 문구를
     * 고르는 기준이 이 하나여야 해서 금액으로 추측할 여지를 남기지 않는다.
     *
     * <p>{@code referenceType}/{@code referenceId} 는 그 사유 안에서 <b>어느 것</b>인지다 —
     * 미션이면 {@code DAILY_MISSION}/미션 이름, 설문이면 {@code SURVEY}/설문 id. 없는 사유도
     * 있으므로 {@code null} 로 실어 보내고, 받는 쪽은 사유만으로도 문구를 만들 수 있어야 한다.
     */
    public void granted(Long userId, Long entryId, int amount, int balanceAfter, String reasonType,
                        String referenceType, String referenceId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "granted");
        event.put("entryId", String.valueOf(entryId));
        event.put("amount", amount);
        event.put("balanceAfter", balanceAfter);
        event.put("reasonType", reasonType);
        event.put("referenceType", referenceType);
        event.put("referenceId", referenceId);
        event.put("occurredAt", Instant.now().toString());
        publish(userId, event);
    }

    private void publish(Long userId, Map<String, Object> event) {
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
            messaging.convertAndSendToUser(String.valueOf(userId), OWNER_QUEUE, event);
        } catch (RuntimeException failure) {
            log.warn("코인 지급 알림 발행 실패 — userId={} entryId={}", userId, event.get("entryId"), failure);
        }
    }
}
