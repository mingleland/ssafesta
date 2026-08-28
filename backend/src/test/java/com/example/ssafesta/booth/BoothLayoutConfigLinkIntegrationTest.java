package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * Linking content to placed objects (spec 005 US2).
 *
 * <p>The one that matters is {@link #anotherBoothsAgentCannotBePublished()}: without it, vector
 * isolation (헌법 17조) would depend on the editor never sending someone else's id.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutConfigLinkIntegrationTest {

    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void anAgentOfThisBoothPublishesFine() {
        Owner owner = leasedOwner("자기에이전트");
        long agentId = createAgent(owner.boothId(), "우리 직원");
        layouts.saveDraft(owner.boothId(), owner.userId(), aiLayout(agentId));

        assertEquals(1, layouts.publish(owner.boothId(), owner.userId()).publishedVersion());
    }

    @Test
    void anotherBoothsAgentCannotBePublished() {
        Owner mine = leasedOwner("내부스");
        Owner theirs = leasedOwner("남부스");
        long strangerAgent = createAgent(theirs.boothId(), "남의 직원");
        layouts.saveDraft(mine.boothId(), mine.userId(), aiLayout(strangerAgent));

        LayoutValidationFailedException failure = assertThrows(LayoutValidationFailedException.class,
                () -> layouts.publish(mine.boothId(), mine.userId()));

        assertTrue(failure.errors().stream().anyMatch(error -> "CONFIG_NOT_OWNED".equals(error.rule())),
                "다른 부스의 콘텐츠 연결은 공개를 막아야 합니다: " + failure.errors());
    }

    @Test
    void anInactiveAgentIsNotAcceptedEither() {
        Owner owner = leasedOwner("비활성에이전트");
        long agentId = createAgent(owner.boothId(), "쉬는 직원");
        jdbc.update("UPDATE ai_agents SET status = 'DISABLED' WHERE id = ?", agentId);
        layouts.saveDraft(owner.boothId(), owner.userId(), aiLayout(agentId));

        assertThrows(LayoutValidationFailedException.class,
                () -> layouts.publish(owner.boothId(), owner.userId()));
    }

    /** Editing is not publishing: a half-built booth may point at content that is not there yet. */
    @Test
    void savingAcceptsAReferenceThatPublishingWouldReject() {
        Owner mine = leasedOwner("저장은허용");
        Owner theirs = leasedOwner("남부스2");
        long strangerAgent = createAgent(theirs.boothId(), "남의 직원2");

        var outcome = layouts.saveDraft(mine.boothId(), mine.userId(), aiLayout(strangerAgent));

        assertEquals(1L, outcome.draft().getRevision());
    }

    @Test
    void anUnlinkedFunctionalObjectPublishesWithAWarning() {
        Owner owner = leasedOwner("미연결");
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"ai-1","type":"AI_AGENT","position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                """);

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        // C-04 default: allowed, but never silently — the warning rides in the response.
        assertEquals(1, outcome.publishedVersion());
        assertTrue(outcome.warnings().stream().anyMatch(w -> "CONFIG_NOT_LINKED".equals(w.rule())),
                "미연결은 경고로 알려야 합니다: " + outcome.warnings());
    }

    /** A type nobody can check yet says so, rather than passing as if it had been verified. */
    @Test
    void anUncheckableTypeIsReportedAsUnverified() {
        Owner owner = leasedOwner("검증불가");
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"panel-1","type":"PROJECT_PANEL","configId":4242,
                   "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                """);

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertTrue(outcome.warnings().stream().anyMatch(w -> "CONFIG_UNVERIFIED".equals(w.rule())),
                "확인할 수 없다는 사실이 조용해지면 안 됩니다: " + outcome.warnings());
    }

    /**
     * {@code LAPTOP} answers a different question for the same warning (spec 016 계약 §3-1).
     *
     * <p>C-01 fixed the homepage URL onto {@code booths.homepage_url}, so a laptop has no
     * {@code configId} to be missing — judging it by one flagged every correctly configured booth.
     * The code and envelope are unchanged; only the predicate moved.
     */
    @Test
    void aLaptopWithoutAHomepageUrlWarns() {
        Owner owner = leasedOwner("노트북미등록");
        layouts.saveDraft(owner.boothId(), owner.userId(), laptopLayout(null));

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertEquals(1, outcome.publishedVersion());
        assertTrue(outcome.warnings().stream().anyMatch(w -> "CONFIG_NOT_LINKED".equals(w.rule())
                        && "홈페이지 주소가 등록되지 않았습니다.".equals(w.message())),
                "URL 미등록 노트북은 경고해야 합니다: " + outcome.warnings());
    }

    /** The false positive this change exists to remove. */
    @Test
    void aLaptopWithAHomepageUrlDoesNotWarn() {
        Owner owner = leasedOwner("노트북등록");
        registerHomepage(owner.boothId(), "https://team.example.com");
        layouts.saveDraft(owner.boothId(), owner.userId(), laptopLayout(null));

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertTrue(outcome.warnings().stream().noneMatch(w -> "CONFIG_NOT_LINKED".equals(w.rule())),
                "URL 이 등록된 부스에 미연결 경고가 남으면 상시 오탐입니다: " + outcome.warnings());
    }

    /** Nothing to click, nothing to warn about — the warning exists to protect a visitor's click. */
    @Test
    void aBoothWithNoLaptopIsNotWarnedAboutItsMissingHomepage() {
        Owner owner = leasedOwner("노트북없음");
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"deco-1","type":"DECORATION","position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                """);

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertTrue(outcome.warnings().stream().noneMatch(w -> "CONFIG_NOT_LINKED".equals(w.rule())),
                "노트북이 없으면 홈페이지 경고를 낼 일이 없습니다: " + outcome.warnings());
    }

    /**
     * FE was told not to send one (계약 §3-1 통보 1), and this is what happens if it does.
     *
     * <p>The laptop branch falls through to the {@code configId} chain on purpose, so an id that
     * should not be there is reported rather than ignored.
     */
    @Test
    void aLaptopCarryingAConfigIdIsReportedAsUnverified() {
        Owner owner = leasedOwner("노트북configId");
        registerHomepage(owner.boothId(), "https://team.example.com");
        layouts.saveDraft(owner.boothId(), owner.userId(), laptopLayout(4242));

        var outcome = layouts.publish(owner.boothId(), owner.userId());

        assertTrue(outcome.warnings().stream().anyMatch(w -> "CONFIG_UNVERIFIED".equals(w.rule())),
                "LAPTOP 은 연결 대상을 서버가 확인할 수 없습니다: " + outcome.warnings());
    }

    /** Written straight to the column — spec 016's endpoint has its own test for the write path. */
    private void registerHomepage(Long boothId, String url) {
        jdbc.update("UPDATE booths SET homepage_url = ? WHERE id = ?", url, boothId);
    }

    private String laptopLayout(Integer configId) {
        String config = configId == null ? "" : "\"configId\":%d,".formatted(configId);
        return """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"laptop-1","type":"LAPTOP",%s
                   "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                """.formatted(config);
    }

    private String aiLayout(long agentId) {
        return """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"ai-1","type":"AI_AGENT","configId":%d,
                   "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                """.formatted(agentId);
    }

    private long createAgent(Long boothId, String name) {
        jdbc.update("""
                INSERT INTO ai_agents (booth_id, name, role_code, tone_code, system_prompt)
                VALUES (?, ?, 'GUIDE', 'FRIENDLY', '안내합니다.')
                """, boothId, name);
        return jdbc.queryForObject(
                "SELECT id FROM ai_agents WHERE booth_id = ? ORDER BY id DESC LIMIT 1", Long.class, boothId);
    }

    private Owner leasedOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, userId);
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
