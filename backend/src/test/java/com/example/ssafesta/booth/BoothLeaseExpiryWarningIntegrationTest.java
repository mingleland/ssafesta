package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies the D07 reminder is private, one-time, and never sent for a lease already expired. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BoothLeaseExpiryWarningIntegrationTest {

    @Autowired private BoothLeaseExpiryWarningService warnings;
    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SimpMessagingTemplate messaging;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void sendsOnePrivateWarningWhenAnActiveLeaseHasOneHourLeft() {
        Long userId = createMemberWithWallet(users, wallets, "만료알림");
        BoothLease lease = leaseService.lease(userId, freeRentableSlot(), 1).lease();
        jdbc.update("UPDATE booth_leases SET ends_at = now() + interval '59 minutes' WHERE id = ?", lease.getId());

        assertEquals(1, warnings.notifyExpiringLeases());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(eq(String.valueOf(userId)),
                eq(BoothLeaseExpiryWarningPublisher.DESTINATION), event.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) event.getValue();
        assertEquals("lease-expiring", body.get("type"));
        assertEquals(String.valueOf(lease.getId()), body.get("leaseId"));
        assertEquals(String.valueOf(lease.getBoothId()), body.get("boothId"));
        assertNotNull(body.get("endsAt"));
        assertNotNull(jdbc.queryForObject("SELECT expiry_warning_sent_at FROM booth_leases WHERE id = ?",
                Object.class, lease.getId()));

        clearInvocations(messaging);
        assertEquals(0, warnings.notifyExpiringLeases(), "이미 보낸 임대는 다음 스캔에서 다시 잡으면 안 됩니다.");
        verify(messaging, never()).convertAndSendToUser(anyString(),
                eq(BoothLeaseExpiryWarningPublisher.DESTINATION), any());
    }

    @Test
    void leavesLeasesWithMoreThanOneHourAndAlreadyExpiredLeasesAlone() {
        Long laterUser = createMemberWithWallet(users, wallets, "아직이름");
        BoothLease later = leaseService.lease(laterUser, freeRentableSlot(), 1).lease();
        jdbc.update("UPDATE booth_leases SET ends_at = now() + interval '61 minutes' WHERE id = ?", later.getId());

        Long expiredUser = createMemberWithWallet(users, wallets, "이미만료");
        BoothLease expired = leaseService.lease(expiredUser, freeRentableSlot(), 1).lease();
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 minute' WHERE id = ?", expired.getId());

        assertEquals(0, warnings.notifyExpiringLeases());
        // destination 을 한정해 본다 — 여기서 확인할 것은 "만료 알림이 가지 않는다" 이지 "어떤
        // 개인 알림도 없다" 가 아니다. 셋업의 지갑 생성이 코인 지급 알림을 내므로(S15P21A604-920)
        // 전체 금지는 이 테스트와 무관한 이유로 깨진다.
        verify(messaging, never()).convertAndSendToUser(anyString(),
                eq(BoothLeaseExpiryWarningPublisher.DESTINATION), any());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM booth_leases "
                        + "WHERE id IN (?, ?) AND expiry_warning_sent_at IS NOT NULL",
                Integer.class, later.getId(), expired.getId()));
    }

    private Long freeRentableSlot() {
        return slots.findAllOrdered().stream()
                .filter(BoothSlot::isRentable)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), java.time.Instant.now()).isEmpty())
                .findFirst().orElseThrow().getId();
    }
}
