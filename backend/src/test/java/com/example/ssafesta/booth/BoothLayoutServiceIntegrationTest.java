package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.decorations;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Saving and publishing (spec 005 US1, data-model §4). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutServiceIntegrationTest {

    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothLayoutQueryService queries;
    @Autowired private BoothRepository booths;
    @Autowired private BoothLayoutPublishedVersionRepository published;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void savingADraftDoesNotPublishAnything() {
        Owner owner = leasedOwner("저장만");

        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        assertNull(booths.findById(owner.boothId()).orElseThrow().getPublishedLayoutVersion(),
                "저장만으로 공개되면 SC-003 위반입니다.");
        assertThrows(LayoutNotPublishedException.class, () -> queries.findPublished(owner.boothId()));
    }

    @Test
    void publishingMovesThePointerInTheSameTransaction() {
        Owner owner = leasedOwner("공개");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertEquals(1, outcome.publishedVersion());
        assertEquals(1, booths.findById(owner.boothId()).orElseThrow().getPublishedLayoutVersion());
        assertEquals(1, published.findAllByBoothIdOrderByVersionNoDesc(owner.boothId()).size());
    }

    /** I-7: the published copy is a snapshot, not a window onto the draft. */
    @Test
    void editingAfterPublishingDoesNotChangeWhatVisitorsSee() {
        Owner owner = leasedOwner("스냅샷");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0, decorations(3)));
        layouts.publish(owner.boothId(), owner.userId());

        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(1, decorations(1)));

        assertEquals(3, queries.findPublished(owner.boothId()).objects().size(),
                "공개본은 공개 시점 그대로여야 합니다 (FR-006).");
        assertEquals(1, queries.findDraft(owner.boothId(), owner.userId()).orElseThrow().objects().size());
    }

    @Test
    void versionsCountUpwards() {
        Owner owner = leasedOwner("회차증가");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        layouts.publish(owner.boothId(), owner.userId());
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(1));

        assertEquals(2, layouts.publish(owner.boothId(), owner.userId()).publishedVersion());
        assertEquals(2, queries.findPublished(owner.boothId()).version());
    }

    @Test
    void anExpiredBoothCannotPublish() {
        Owner owner = leasedOwner("만료공개");
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0));
        BoothLayoutTestSupport.expireLease(jdbc, owner.boothId());

        assertThrows(BoothExpiredException.class, () -> layouts.publish(owner.boothId(), owner.userId()));
    }

    @Test
    void publishingWithoutADraftIsRefusedWithAReason() {
        Owner owner = leasedOwner("초안없음");

        LayoutValidationFailedException failure = assertThrows(LayoutValidationFailedException.class,
                () -> layouts.publish(owner.boothId(), owner.userId()));

        assertTrue(failure.errors().stream().anyMatch(error -> "NO_DRAFT".equals(error.rule())),
                "무엇이 없는지 알려야 합니다: " + failure.errors());
    }

    @Test
    void aRejectedPublishLeavesNothingBehind() {
        Owner owner = leasedOwner("검증실패");
        // 13 objects: over the cap, so validation stops the publish.
        layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0, decorations(12)));
        layouts.publish(owner.boothId(), owner.userId());
        jdbc.update("UPDATE booth_layout_drafts SET layout_json = ?::jsonb WHERE booth_id = ?",
                """
                {"schemaVersion":1,"template":"DEFAULT","objects":%s}
                """.formatted(decorations(13)), owner.boothId());

        assertThrows(LayoutValidationFailedException.class,
                () -> layouts.publish(owner.boothId(), owner.userId()));

        assertEquals(1, booths.findById(owner.boothId()).orElseThrow().getPublishedLayoutVersion(),
                "실패한 공개가 포인터를 옮기면 안 됩니다.");
        assertEquals(1, published.highestVersionNo(owner.boothId()),
                "실패한 공개가 버전 행을 남기면 안 됩니다.");
    }

    @Test
    void anOverSizedDraftIsRefusedAtSaveTime() {
        Owner owner = leasedOwner("상한");

        LayoutValidationFailedException failure = assertThrows(LayoutValidationFailedException.class,
                () -> layouts.saveDraft(owner.boothId(), owner.userId(), saveRequest(0, decorations(13))));

        assertTrue(failure.errors().stream().anyMatch(error -> "OBJECT_LIMIT".equals(error.rule())),
                "공개가 아니라 저장 시점에 막아야 layout_json이 부풀지 않습니다: " + failure.errors());
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
