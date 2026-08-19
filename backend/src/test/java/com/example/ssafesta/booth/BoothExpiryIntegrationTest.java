package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Expiry, re-lease and content isolation (spec 004 User Story 3). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothExpiryIntegrationTest {

    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothQueryService queries;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anExpiredLeaseFreesTheSlotWithoutAnySchedulerRunning() {
        Long userId = createMemberWithWallet(users, wallets, "만료");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        expire(lease.getId());

        BoothQueryService.SlotView view = slotView(slotId, null);

        assertEquals("AVAILABLE", view.status());
        assertFalse(view.entryAvailable());
        assertNull(view.remainingSeconds());
        // The row is still ACTIVE in the database — the read decides validity, not a batch job.
        assertEquals(LeaseStatus.ACTIVE, leases.findById(lease.getId()).orElseThrow().getStatus());
    }

    @Test
    void anExpiredBoothRefusesVisitorsWithItsReason() {
        Long userId = createMemberWithWallet(users, wallets, "만료접근");
        Long slotId = freeSlotId();
        BoothLease lease = leaseService.lease(userId, slotId, 1).lease();
        Long boothId = lease.getBoothId();
        // Visible while the lease is valid.
        assertTrue(queries.findPublicBooth(boothId).entryAvailable());

        expire(lease.getId());

        assertThrows(BoothExpiredException.class, () -> queries.findPublicBooth(boothId));
    }

    @Test
    void anExpiredLeaseNoLongerOccupiesTheMembersOneLeaseAllowance() {
        Long userId = createMemberWithWallet(users, wallets, "한도해제");
        Long firstSlot = freeSlotId();
        BoothLease first = leaseService.lease(userId, firstSlot, 1).lease();
        expire(first.getId());

        // Would throw ActiveLeaseLimitException if the expiry predicate were missing (I-3).
        BoothLease second = leaseService.lease(userId, freeSlotId(), 1).lease();

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void reLeasingTransitionsTheStaleLeaseAndReleasesTheOldBooth() {
        Long previous = createMemberWithWallet(users, wallets, "이전임차인");
        Long next = createMemberWithWallet(users, wallets, "새임차인");
        Long slotId = freeSlotId();
        BoothLease old = leaseService.lease(previous, slotId, 1).lease();
        expire(old.getId());

        BoothLease fresh = leaseService.lease(next, slotId, 1).lease();

        assertEquals(LeaseStatus.EXPIRED, leases.findById(old.getId()).orElseThrow().getStatus(),
                "이전 임대가 EXPIRED로 전이돼야 재임대가 가능하다 (FR-017)");
        assertEquals(LeaseStatus.ACTIVE, fresh.getStatus());
        assertEquals(slotId, fresh.getSlotId());
        Booth oldBooth = booths.findById(old.getBoothId()).orElseThrow();
        assertNull(oldBooth.getCurrentSlotId(), "이전 부스의 슬롯 연결이 끊겨야 한다");
        assertEquals(BoothStatus.INACTIVE, oldBooth.getStatus());
    }

    @Test
    void aNewTenantNeverReceivesThePreviousOwnersBooth() {
        Long previous = createMemberWithWallet(users, wallets, "원소유자");
        Long next = createMemberWithWallet(users, wallets, "재임대자");
        Long slotId = freeSlotId();
        BoothLease old = leaseService.lease(previous, slotId, 1).lease();
        expire(old.getId());

        BoothLease fresh = leaseService.lease(next, slotId, 1).lease();

        assertNotEquals(old.getBoothId(), fresh.getBoothId(),
                "재임대자는 자기 부스를 받아야 한다 (C-01, SC-004)");
        assertTrue(booths.findById(fresh.getBoothId()).orElseThrow().isOwnedBy(next));
        assertTrue(booths.findById(old.getBoothId()).orElseThrow().isOwnedBy(previous));
    }

    @Test
    void thePreviousOwnersBoothSurvivesExpiry() {
        Long previous = createMemberWithWallet(users, wallets, "보존");
        Long next = createMemberWithWallet(users, wallets, "다음사람");
        Long slotId = freeSlotId();
        BoothLease old = leaseService.lease(previous, slotId, 1).lease();
        expire(old.getId());
        leaseService.lease(next, slotId, 1);

        BoothQueryService.MyBoothView mine = queries.findMyBooth(previous).orElseThrow();

        assertEquals(old.getBoothId(), mine.boothId(), "부스는 삭제되지 않는다 (FR-010)");
        assertEquals(BoothStatus.INACTIVE.name(), mine.status());
        assertNull(mine.lease());
    }

    @Test
    void theSameMemberCanLeaseTheSameSlotAgainAndPaysAgain() {
        Long userId = createMemberWithWallet(users, wallets, "재임대동일인");
        Long slotId = freeSlotId();
        BoothLease first = leaseService.lease(userId, slotId, 1).lease();
        expire(first.getId());
        int afterFirst = wallets.balanceOf(userId);

        BoothLease second = leaseService.lease(userId, slotId, 1).lease();

        assertNotEquals(first.getId(), second.getId());
        // A key derived from (user, slot) would have made this second lease free (research R-04).
        assertTrue(wallets.balanceOf(userId) < afterFirst, "재임대는 다시 결제돼야 한다");
        assertEquals(first.getBoothId(), second.getBoothId(), "같은 사람은 자기 부스를 이어서 쓴다");
        BoothTestSupport.assertBalanceMatchesLedger(wallets, userId);
    }

    @Test
    void aMemberWhoNeverLeasedHasNoBooth() {
        Long userId = createMemberWithWallet(users, wallets, "부스없음");

        assertTrue(queries.findMyBooth(userId).isEmpty());
    }

    /** Pushes a lease into the past without touching its status — that is what a real expiry is. */
    private void expire(Long leaseId) {
        jdbc.update("UPDATE booth_leases SET starts_at = now() - interval '25 hours', "
                + "ends_at = now() - interval '1 hour' WHERE id = ?", leaseId);
    }

    private BoothQueryService.SlotView slotView(Long slotId, Long viewerId) {
        return queries.listSlots(viewerId).stream()
                .filter(slot -> slot.slotId().equals(slotId)).findFirst().orElseThrow();
    }

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
