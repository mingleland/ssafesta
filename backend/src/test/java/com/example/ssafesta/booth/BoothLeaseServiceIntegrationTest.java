package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.assertBalanceMatchesLedger;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.CoinLedgerEntryRepository;
import com.example.ssafesta.wallet.CoinSpendCommand;
import com.example.ssafesta.wallet.InsufficientCoinException;
import com.example.ssafesta.wallet.WalletService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Leasing a slot (spec 004 User Story 1). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLeaseServiceIntegrationTest {

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothQueryService queries;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private CoinLedgerEntryRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private LeaseProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    /** V5의 7개를 V12가 12개로 확장했다 — Unity 축제 존 12실과 맞춘 값이다 (#62). */
    @Test
    void twelveUserRentalSlotsAreSeeded() {
        List<BoothSlot> rentable = slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL).toList();

        assertEquals(12, rentable.size());
        assertTrue(rentable.stream().allMatch(slot -> slot.getFloorNo() == 11));
        assertTrue(rentable.stream().allMatch(BoothSlot::isRentable));
    }

    @Test
    void leasingChargesCoinsAndCreatesTheLease() {
        Long userId = createMemberWithWallet(users, wallets, "임대");
        int before = wallets.balanceOf(userId);
        Long slotId = freeSlotId();

        BoothLeaseService.LeaseOutcome outcome = leaseService.lease(userId, slotId, 1);

        assertFalse(outcome.alreadyHeld());
        assertEquals(before - properties.priceCoin(), outcome.balanceAfter());
        assertEquals(before - properties.priceCoin(), wallets.balanceOf(userId));
        BoothLease lease = leases.findById(outcome.lease().getId()).orElseThrow();
        assertEquals(LeaseStatus.ACTIVE, lease.getStatus());
        assertEquals(properties.priceCoin(), lease.getChargedCoin());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void theLedgerRecordsTheLeasePaymentAgainstTheLease() {
        Long userId = createMemberWithWallet(users, wallets, "원장연결");
        Long slotId = freeSlotId();

        BoothLeaseService.LeaseOutcome outcome = leaseService.lease(userId, slotId, 1);

        var entry = ledger.findByIdempotencyKey("LEASE_PAYMENT:BOOTH_LEASE:" + outcome.lease().getId())
                .orElseThrow();
        assertEquals(-properties.priceCoin(), entry.getAmount());
        assertEquals("LEASE_PAYMENT", entry.getReasonType());
        assertEquals("BOOTH_LEASE", entry.getReferenceType());
        assertEquals(String.valueOf(outcome.lease().getId()), entry.getReferenceId());
    }

    @Test
    void theServerDecidesTheEndTimeNotTheRequest() {
        Long userId = createMemberWithWallet(users, wallets, "기간");
        Long slotId = freeSlotId();

        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();

        assertEquals(Duration.ofHours(24), Duration.between(lease.getStartsAt(), lease.getEndsAt()));
        assertTrue(lease.remainingSecondsAt(Instant.now()) > 0);
    }

    @Test
    void theBoothIsAttachedToTheSlotAndOwnedByTheLessee() {
        Long userId = createMemberWithWallet(users, wallets, "부스연결");
        Long slotId = freeSlotId();

        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();

        Booth booth = booths.findById(lease.getBoothId()).orElseThrow();
        assertTrue(booth.isOwnedBy(userId));
        assertEquals(slotId, booth.getCurrentSlotId());
        assertEquals(BoothStatus.ACTIVE, booth.getStatus());
    }

    @Test
    void anUnaffordableLeaseChangesNothingAtAll() {
        Long userId = createMemberWithWallet(users, wallets, "잔액부족");
        // Drain the wallet through the ledger so the balance and the ledger stay consistent.
        wallets.spend(new CoinSpendCommand(userId, wallets.balanceOf(userId),
                "ADMIN_ADJUSTMENT", null, null, "DRAIN:" + userId));
        Long slotId = freeSlotId();
        long leasesBefore = leases.count();

        assertThrows(InsufficientCoinException.class, () -> leaseService.lease(userId, slotId, 1));

        assertEquals(0, wallets.balanceOf(userId));
        assertEquals(leasesBefore, leases.count(), "임대가 생기면 안 됩니다.");
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aMemberCannotHoldTwoLeasesAtOnce() {
        Long userId = createMemberWithWallet(users, wallets, "한도");
        Long first = freeSlotId();
        leaseService.lease(userId, first, 1);
        int afterFirst = wallets.balanceOf(userId);
        Long second = freeSlotId();

        assertThrows(ActiveLeaseLimitException.class, () -> leaseService.lease(userId, second, 1));

        assertEquals(afterFirst, wallets.balanceOf(userId), "거부된 임대는 코인을 쓰지 않습니다.");
        assertTrue(leases.findValidBySlotId(second, Instant.now()).isEmpty());
    }

    @Test
    void aSlotHeldBySomeoneElseIsRefused() {
        Long owner = createMemberWithWallet(users, wallets, "선점자");
        Long other = createMemberWithWallet(users, wallets, "후발자");
        Long slotId = freeSlotId();
        leaseService.lease(owner, slotId, 1);
        int otherBefore = wallets.balanceOf(other);

        assertThrows(SlotAlreadyLeasedException.class, () -> leaseService.lease(other, slotId, 1));

        assertEquals(otherBefore, wallets.balanceOf(other));
        assertBalanceMatchesLedger(wallets, other);
    }

    @Test
    void theTenantsRetryReturnsTheExistingLeaseWithoutChargingAgain() {
        Long userId = createMemberWithWallet(users, wallets, "재요청");
        Long slotId = freeSlotId();
        BoothLeaseService.LeaseOutcome first = leaseService.lease(userId, slotId, 1);
        int afterFirst = wallets.balanceOf(userId);

        BoothLeaseService.LeaseOutcome retry = leaseService.lease(userId, slotId, 1);

        assertTrue(retry.alreadyHeld());
        assertEquals(first.lease().getId(), retry.lease().getId());
        assertEquals(afterFirst, wallets.balanceOf(userId));
        assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aNonRentableSlotIsRefused() {
        Long userId = createMemberWithWallet(users, wallets, "관리슬롯");
        jdbc.update("INSERT INTO booth_slots (slot_code, floor_no, slot_type, status) VALUES (?, 11, 'ADMIN', 'AVAILABLE')",
                "ADMIN-" + System.nanoTime());
        Long adminSlotId = jdbc.queryForObject(
                "SELECT id FROM booth_slots WHERE slot_type = 'ADMIN' ORDER BY id DESC LIMIT 1", Long.class);

        assertThrows(SlotNotRentableException.class, () -> leaseService.lease(userId, adminSlotId, 1));
    }

    @Test
    void onlyASingleDayTermIsAccepted() {
        Long userId = createMemberWithWallet(users, wallets, "기간거부");
        Long slotId = freeSlotId();

        assertThrows(IllegalArgumentException.class, () -> leaseService.lease(userId, slotId, 7));
        assertTrue(leases.findValidBySlotId(slotId, Instant.now()).isEmpty());
    }

    @Test
    void anUnknownSlotIsReported() {
        Long userId = createMemberWithWallet(users, wallets, "없는슬롯");

        assertThrows(SlotNotFoundException.class, () -> leaseService.lease(userId, 999_999L, 1));
    }

    @Test
    void aLeasedSlotShowsAsOccupiedWithRemainingTime() {
        Long userId = createMemberWithWallet(users, wallets, "조회");
        Long slotId = freeSlotId();
        leaseService.lease(userId, slotId, 1);

        BoothQueryService.SlotView view = queries.listSlots(userId).stream()
                .filter(slot -> slot.slotId().equals(slotId)).findFirst().orElseThrow();

        assertEquals("OCCUPIED", view.status());
        assertTrue(view.entryAvailable());
        assertTrue(view.mine());
        assertNotNull(view.remainingSeconds());
        assertTrue(view.remainingSeconds() > 0);
    }

    /** A slot with no valid lease right now. Tests share one database, so pick dynamically. */
    private Long freeSlotId() {
        Instant now = Instant.now();
        return slots.findAllOrdered().stream()
                .filter(slot -> slot.getSlotType() == SlotType.USER_RENTAL)
                .filter(slot -> leases.findValidBySlotId(slot.getId(), now).isEmpty())
                .map(BoothSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("빈 USER_RENTAL 슬롯이 없습니다."));
    }
}
