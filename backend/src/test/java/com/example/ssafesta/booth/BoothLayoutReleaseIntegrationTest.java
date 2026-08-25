package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * Re-leasing does not republish (spec 005 US3, FR-011/FR-017).
 *
 * <p>The failure this guards against is quiet: a booth comes back and the previous tenancy's
 * published layout is simply there again, with nobody having pressed publish.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutReleaseIntegrationTest {

    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothLayoutQueryService queries;
    @Autowired private BoothLeaseService leaseService;
    @Autowired private BoothRepository booths;
    @Autowired private BoothSlotRepository slots;
    @Autowired private BoothLeaseRepository leases;
    @Autowired private BoothLayoutDraftRepository drafts;
    @Autowired private BoothLayoutPublishedVersionRepository published;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void reLeasingClearsThePublishedPointerButKeepsEverything() {
        Long userId = createMemberWithWallet(users, wallets, "재임대");
        Long slotId = freeSlotId();
        Long boothId = leaseService.lease(userId, slotId, 1).lease().getBoothId();
        layouts.saveDraft(boothId, userId, saveRequest(0));
        layouts.publish(boothId, userId);
        BoothLayoutTestSupport.expireLease(jdbc, boothId);

        leaseService.lease(userId, freeSlotId(), 1);

        assertNull(booths.findById(boothId).orElseThrow().getPublishedLayoutVersion(),
                "재임대는 작업본 상태로 시작해야 합니다 (FR-011).");
        assertTrue(drafts.findById(boothId).isPresent(), "작업본은 보존되어야 합니다.");
        assertEquals(1, published.findAllByBoothIdOrderByVersionNoDesc(boothId).size(),
                "공개 이력은 지우지 않습니다 (C-07).");
    }

    @Test
    void aReleasedBoothServesNoLayout() {
        Long userId = createMemberWithWallet(users, wallets, "해제조회");
        Long boothId = leaseService.lease(userId, freeSlotId(), 1).lease().getBoothId();
        layouts.saveDraft(boothId, userId, saveRequest(0));
        layouts.publish(boothId, userId);
        BoothLayoutTestSupport.expireLease(jdbc, boothId);
        leaseService.lease(userId, freeSlotId(), 1);

        assertThrows(LayoutNotPublishedException.class, () -> queries.findPublished(boothId));
    }

    @Test
    void versionNumbersContinueRatherThanRestart() {
        Long userId = createMemberWithWallet(users, wallets, "회차유지");
        Long boothId = leaseService.lease(userId, freeSlotId(), 1).lease().getBoothId();
        layouts.saveDraft(boothId, userId, saveRequest(0));
        layouts.publish(boothId, userId);
        BoothLayoutTestSupport.expireLease(jdbc, boothId);
        leaseService.lease(userId, freeSlotId(), 1);

        // Republishing after a gap is version 2, not version 1 again: history stayed.
        assertEquals(2, layouts.publish(boothId, userId).publishedVersion());
    }

    @Test
    void theOwnerCanStillEditWhileUnleased() {
        Long userId = createMemberWithWallet(users, wallets, "미임대편집");
        Long boothId = leaseService.lease(userId, freeSlotId(), 1).lease().getBoothId();
        layouts.saveDraft(boothId, userId, saveRequest(0));
        BoothLayoutTestSupport.expireLease(jdbc, boothId);

        // Preserved content has to remain reachable, otherwise "보존" means nothing in practice.
        var outcome = layouts.saveDraft(boothId, userId, saveRequest(1));

        assertEquals(2L, outcome.draft().getRevision());
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
