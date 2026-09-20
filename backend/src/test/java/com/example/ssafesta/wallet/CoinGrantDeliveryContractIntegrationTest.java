package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.auth.MemberSessionService;
import com.example.ssafesta.consultation.ws.WsTokenService;
import com.example.ssafesta.user.UserRepository;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 코인 지급 알림의 전달 계약 (S15P21A604-923).
 *
 * <p>Mockito 로 {@code SimpMessagingTemplate} 을 검증하는 기존 테스트
 * ({@link CoinGrantNotificationIntegrationTest})는 <b>발행 호출</b>만 본다. 그래서 구독자가 없는
 * 순간에 발행된 지급도 통과한다 — 실제 사용자는 그 알림을 영영 받지 못하는데도 그렇다. 여기서는
 * 진짜 STOMP 클라이언트를 붙여 <b>도착 여부</b>를 본다.
 *
 * <p>한 시나리오에 두 가지를 함께 고정한다.
 * <ol>
 *   <li>구독 전에 발행된 지급(가입 지급)은 <b>도착하지 않는다</b> — STOMP 에 재전송이 없다.</li>
 *   <li>그 원장 항목은 {@code GET /api/v1/wallets/me/transactions} 로 <b>회수된다</b>, 그리고
 *       구독 중에 도착한 이벤트는 같은 목록의 항목과 같은 식별자를 갖는다.</li>
 * </ol>
 *
 * <p>둘째가 첫째와 한 테스트에 있는 이유는, 유실을 확인하는 것만으로는 계약이 서지 않기
 * 때문이다. 유실을 인정하는 대신 <b>회수 경로가 실제로 같은 항목을 돌려준다</b>는 것까지가 계약이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoinGrantDeliveryContractIntegrationTest {

    private static final String OWNER_QUEUE = "/user/queue/coin";

    @LocalServerPort private int port;

    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private WsTokenService wsTokens;
    @Autowired private MemberSessionService sessions;
    @Autowired private SimpUserRegistry userRegistry;

    @Test
    void grantsPublishedBeforeSubscribeNeverArriveButAreRecoverableFromTheLedger() throws Exception {
        Long userId = createMember(users, "전달계약");
        // 가입 지급. 이 순간 이 회원의 소켓은 존재하지 않는다 — 실제 가입 흐름과 같다.
        wallets.openWallet(userId);

        BlockingQueue<Map<String, Object>> arrived = new LinkedBlockingQueue<>();
        StompSession session = connect(userId);
        session.subscribe(OWNER_QUEUE, collectInto(arrived));
        awaitSubscriptionRegistered(userId);

        assertNull(arrived.poll(1, TimeUnit.SECONDS),
                "구독 전에 발행된 지급은 STOMP 로 도착하지 않는다 — 재전송이 없다");

        // 이 REST 호출의 preHandle 이 오늘치 일일 지급을 만든다. 지금은 구독 중이므로 그 알림은
        // 도착하고, 같은 응답 본문에는 앞서 유실된 가입 지급도 함께 들어 있다.
        List<Map<String, Object>> entries = transactionsOf(userId);

        Map<String, Object> initialGrant = entryByReason(entries, CoinReason.INITIAL_GRANT);
        assertNotNull(initialGrant, "유실된 가입 지급은 거래내역 REST 로 회수된다");

        Map<String, Object> event = arrived.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "구독 이후에 발행된 지급은 도착한다");
        assertEquals(CoinReason.DAILY_GRANT, event.get("reasonType"));

        Map<String, Object> dailyGrant = entryByReason(entries, CoinReason.DAILY_GRANT);
        assertNotNull(dailyGrant, "도착한 지급도 원장에 있다 — 정본은 REST 다");
        // STOMP 는 문자열, REST 는 숫자로 같은 원장 id 를 싣는다. 클라이언트가 두 경로를 합칠 때
        // 문자열로 맞춰 비교해야 같은 항목이 두 번 보이지 않는다 — 계약의 중복 제거 키다.
        assertEquals(String.valueOf(dailyGrant.get("id")), event.get("entryId"));
        assertTrue(entries.stream().map(entry -> String.valueOf(entry.get("id"))).distinct().count() >= 2,
                "가입 지급과 일일 지급은 서로 다른 원장 항목이다");

        session.disconnect();
    }

    private StompSession connect(Long userId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + wsTokens.issue(userId).token());
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
    }

    /**
     * 브로커에 구독이 등록될 때까지 기다린다.
     *
     * <p>{@code SUBSCRIBE} 는 비동기 inbound 채널로 가고 simple broker 는 RECEIPT 를 돌려주지
     * 않는다 — 클라이언트에서 확인할 방법이 없어 서버 쪽 레지스트리를 본다. 이 대기가 없으면
     * "구독 이후 도착" 단언이 등록 전에 발행된 이벤트를 기다리다 간헐 실패한다.
     */
    private void awaitSubscriptionRegistered(Long userId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var user = userRegistry.getUser(String.valueOf(userId));
            if (user != null && user.getSessions().stream()
                    .anyMatch(simpSession -> !simpSession.getSubscriptions().isEmpty())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("구독이 브로커에 등록되지 않았습니다 — userId=" + userId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transactionsOf(Long userId) {
        Map<String, Object> page = RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/v1/wallets/me/transactions")
                .header("Authorization", "Bearer " + sessions.issue(userId).accessToken())
                .retrieve()
                .body(Map.class);
        return (List<Map<String, Object>>) Objects.requireNonNull(page).get("content");
    }

    private Map<String, Object> entryByReason(List<Map<String, Object>> entries, String reasonType) {
        return entries.stream()
                .filter(entry -> reasonType.equals(entry.get("reasonType")))
                .findFirst()
                .orElse(null);
    }

    private StompFrameHandler collectInto(BlockingQueue<Map<String, Object>> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((Map<String, Object>) payload);
            }
        };
    }
}
