package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 코인이 들어오면 본인 큐로 알림이 간다 (S15P21A604-920).
 *
 * <p>별도 클래스인 이유는 {@code @MockitoBean} 이 컨텍스트를 갈라 놓기 때문이다 —
 * {@code WalletServiceIntegrationTest} 의 캐시를 오염시키지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CoinGrantNotificationIntegrationTest {

    private static final String OWNER_QUEUE = "/queue/coin";

    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private DailyCoinGrantService dailyGrants;
    @MockitoBean private SimpMessagingTemplate messaging;

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastEventOf(Long userId) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(eq(String.valueOf(userId)), eq(OWNER_QUEUE), payload.capture());
        return (Map<String, Object>) payload.getValue();
    }

    /**
     * 최초 가입 지급 — 받는 쪽이 "게임 처음 들어와서 받은 코인" 문구를 고르는 근거다.
     */
    @Test
    void signupGrantIsNotifiedAsInitialGrant() {
        Long userId = createMember(users, "최초지급알림");

        wallets.openWallet(userId);

        Map<String, Object> event = lastEventOf(userId);
        assertEquals("granted", event.get("type"));
        assertEquals(CoinReason.INITIAL_GRANT, event.get("reasonType"));
        assertEquals(wallets.balanceOf(userId), event.get("balanceAfter"));
    }

    /** 일일 접속 지급 — 최초 지급과 같은 금액일 수 있어 사유로만 갈린다. */
    @Test
    void dailyGrantIsNotifiedAsDailyGrant() {
        Long userId = createMember(users, "일일지급알림");
        wallets.openWallet(userId);
        clearInvocations(messaging);

        dailyGrants.grantIfDue(userId);

        Map<String, Object> event = lastEventOf(userId);
        assertEquals(CoinReason.DAILY_GRANT, event.get("reasonType"));
    }

    /**
     * 미션 달성 — 어느 미션인지까지 실어야 한다. 사유가 {@code DAILY_MISSION} 하나뿐이라
     * {@code referenceId} 가 없으면 받는 쪽은 미션 이름을 알 길이 없다.
     */
    @Test
    void missionRewardCarriesWhichMission() {
        Long userId = createMember(users, "미션알림");
        wallets.openWallet(userId);
        clearInvocations(messaging);

        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, 30,
                CoinReason.DAILY_MISSION, "DAILY_MISSION", "AI_CONSULT", "coin-notify-mission-" + userId));

        Map<String, Object> event = lastEventOf(userId);
        assertEquals(CoinReason.DAILY_MISSION, event.get("reasonType"));
        assertEquals("DAILY_MISSION", event.get("referenceType"));
        assertEquals("AI_CONSULT", event.get("referenceId"));
        assertEquals(30, event.get("amount"));
        assertEquals(wallets.balanceOf(userId), event.get("balanceAfter"));
    }

    @Test
    void duplicateIdempotencyKeyDoesNotNotifyTwice() {
        Long userId = createMember(users, "멱등알림");
        wallets.openWallet(userId);
        String key = "coin-notify-dup-" + userId;
        clearInvocations(messaging);

        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, 20,
                CoinReason.DAILY_MISSION, null, null, key));
        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, 20,
                CoinReason.DAILY_MISSION, null, null, key));

        verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void spendingDoesNotNotify() {
        Long userId = createMember(users, "차감무알림");
        wallets.openWallet(userId);
        clearInvocations(messaging);

        wallets.spend(new CoinSpendCommand(userId, 10, CoinReason.PURCHASE, null, null,
                "coin-notify-spend-" + userId));

        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void notificationFailureDoesNotUndoTheGrant() {
        Long userId = createMember(users, "알림장애지급");
        wallets.openWallet(userId);
        int before = wallets.balanceOf(userId);
        doThrow(new MessageDeliveryException("브로커 없음"))
                .when(messaging).convertAndSendToUser(anyString(), anyString(), any());

        wallets.credit(new CoinCreditCommand(userId, LedgerEntryType.REWARD, 40,
                CoinReason.DAILY_MISSION, null, null, "coin-notify-broken-" + userId));

        assertEquals(before + 40, wallets.balanceOf(userId));
        assertBalanceMatchesLedger(wallets, userId);
    }
}
