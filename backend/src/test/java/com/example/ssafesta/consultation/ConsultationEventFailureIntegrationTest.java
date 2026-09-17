package com.example.ssafesta.consultation;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothTestSupport;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 알림 브로커가 죽어도 상담은 열린다 (S15P21A604-693).
 *
 * <p>{@code ConsultationEventPublisher} 의 Javadoc 은 "발행 실패가 상담 자체를 되돌리지 않는다" 고
 * 적어 왔지만 코드에는 그 보장이 없었다 — {@code convertAndSend} 가 던지면 {@code @Transactional}
 * 서비스가 롤백돼, 문서가 금지한 "알림이 안 갔다 = 상담이 안 열렸다" 가 그대로 일어났다.
 *
 * <p>별도 클래스인 이유는 {@code @MockitoBean} 이 컨텍스트를 갈라 놓기 때문이다 —
 * {@code ConsultationApiIntegrationTest} 의 캐시를 오염시키지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsultationEventFailureIntegrationTest {

    @Autowired private ConsultationService consultations;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SimpMessagingTemplate messaging;

    @BeforeEach
    void brokerIsDown() {
        BoothTestSupport.releaseAllSlots(jdbc);
        doThrow(new MessageDeliveryException("브로커 없음"))
                .when(messaging).convertAndSend(anyString(), any(Object.class));
        doThrow(new MessageDeliveryException("브로커 없음"))
                .when(messaging).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void requestAcceptAndEndCommitEvenWhenEveryNotificationFails() {
        Long ownerId = createMemberWithWallet(users, wallets, "알림장애");
        Long boothId = booths.save(new Booth(ownerId, "알림장애 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        Long visitorId = createMemberWithWallet(users, wallets, "알림장애방문");

        Long requestId = Long.valueOf(consultations.request(visitorId,
                new ConsultationService.RequestCommand(boothId, null, null)).requestId());
        assertEquals("REQUESTED", statusOf(requestId));

        consultations.accept(requestId, ownerId);
        assertEquals("ACCEPTED", statusOf(requestId));

        consultations.end(requestId, visitorId);
        assertEquals("ENDED", statusOf(requestId));
    }

    private String statusOf(Long requestId) {
        return jdbc.queryForObject("SELECT status FROM consultations WHERE id = ?", String.class, requestId);
    }
}
