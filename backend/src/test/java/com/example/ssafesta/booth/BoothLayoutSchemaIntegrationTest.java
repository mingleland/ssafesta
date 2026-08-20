package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.AccountDeletionService;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V8·V9 and the layout entity mappings (spec 005 Phase 2).
 *
 * <p>Written because the phase ends with a claim — "the schema and entities are ready" — that
 * nothing would otherwise check. Two of these tests cover risks introduced by V8 itself.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutSchemaIntegrationTest {

    private static final String LAYOUT = """
            {"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
              {"objectId":"screen-1","type":"VIDEO_SCREEN",
               "position":{"x":2.123456789,"y":0.0,"z":-3.4},"rotationY":359.9,"configId":152}]}
            """;

    @Autowired private BoothRepository booths;
    @Autowired private BoothLayoutDraftRepository drafts;
    @Autowired private BoothLayoutPublishedVersionRepository published;
    @Autowired private AccountDeletionService deletions;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aDraftKeepsItsJsonExactly() {
        Long boothId = newBooth("초안보관");

        drafts.save(new BoothLayoutDraft(boothId, 1, LAYOUT, ownerOf(boothId)));
        String stored = jdbc.queryForObject(
                "SELECT layout_json::text FROM booth_layout_drafts WHERE booth_id = ?", String.class, boothId);

        // jsonb normalises whitespace and key order, so this is semantic equality, not byte
        // equality (research R-04) — what must survive is the number.
        assertNotNull(stored);
        LayoutJson reread = LayoutJson.parse(stored);
        assertEquals(0, reread.document().objects().get(0).position().x()
                .compareTo(new java.math.BigDecimal("2.123456789")), "좌표 정밀도가 손실되면 안 됩니다: " + stored);
        assertEquals(0, reread.document().objects().get(0).rotationY()
                .compareTo(new java.math.BigDecimal("359.9")), "회전값이 손실되면 안 됩니다: " + stored);
    }

    @Test
    void theDraftPrimaryKeyIsTheBooth() {
        Long boothId = newBooth("초안하나");
        drafts.save(new BoothLayoutDraft(boothId, 1, LAYOUT, ownerOf(boothId)));
        drafts.flush();

        // A second insert for the same booth is the same row — invariant I-1 is the PK itself.
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM booth_layout_drafts WHERE booth_id = ?", Integer.class, boothId));
    }

    @Test
    void thePublishedPointerMustReferToARealVersion() {
        Long boothId = newBooth("포인터");

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbc.update("UPDATE booths SET published_layout_version = 7 WHERE id = ?", boothId);
        }, "존재하지 않는 회차를 가리키는 것을 DB가 막아야 합니다 (I-3).");
    }

    @Test
    void aNullPointerMeansNothingIsPublished() {
        Long boothId = newBooth("미공개");

        // MATCH SIMPLE: with the column NULL the foreign key is not checked at all, which is how
        // "nothing published" stays expressible alongside the constraint.
        jdbc.update("UPDATE booths SET published_layout_version = NULL WHERE id = ?", boothId);

        assertNull(jdbc.queryForObject(
                "SELECT published_layout_version FROM booths WHERE id = ?", Integer.class, boothId));
    }

    @Test
    void withdrawingAnOwnerWhoPublishedStillWorks() {
        Long userId = createMemberWithWallet(users, wallets, "탈퇴공개");
        Long boothId = booths.save(new Booth(userId, "탈퇴 검증 부스")).getId();
        published.save(new BoothLayoutPublishedVersion(boothId, 1, 1, LAYOUT, userId));
        jdbc.update("UPDATE booths SET published_layout_version = 1 WHERE id = ?", boothId);

        // AccountDeletionService deletes booth_layout_published_versions *before* booths. Without
        // ON DELETE SET NULL on V8's foreign key this throws and member withdrawal is broken —
        // and nothing else in the suite would have noticed.
        deletions.deleteUserGraph(userId);

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booths WHERE id = ?", Integer.class, boothId));
    }

    @Test
    void facadeFieldsArePresentAndNullable() {
        Long boothId = newBooth("외관");

        jdbc.update("UPDATE booths SET facade_theme_code = 'SSAFY_BLUE', facade_primary_color = '#1677C8', "
                + "facade_sign_text = 'AI 프로젝트 전시관', facade_logo_url = NULL WHERE id = ?", boothId);

        Booth reloaded = booths.findById(boothId).orElseThrow();
        assertEquals("SSAFY_BLUE", reloaded.getFacadeThemeCode());
        assertEquals("#1677C8", reloaded.getFacadePrimaryColor());
        assertEquals("AI 프로젝트 전시관", reloaded.getFacadeSignText());
        assertNull(reloaded.getFacadeLogoUrl());
    }

    @Test
    void versionNumbersAreCountedPerBooth() {
        Long first = newBooth("회차A");
        Long second = newBooth("회차B");
        published.save(new BoothLayoutPublishedVersion(first, 1, 1, LAYOUT, ownerOf(first)));
        published.save(new BoothLayoutPublishedVersion(first, 2, 1, LAYOUT, ownerOf(first)));

        assertEquals(2, published.highestVersionNo(first));
        assertEquals(0, published.highestVersionNo(second), "공개한 적 없으면 0이어야 합니다.");
    }

    private Long newBooth(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        return booths.save(new Booth(userId, prefix + " 부스")).getId();
    }

    private Long ownerOf(Long boothId) {
        return booths.findById(boothId).orElseThrow().getOwnerUserId();
    }
}
