package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * What goes in comes back out (SC-004, research R-04).
 *
 * <p>Checking that {@code 2.1} survives would prove nothing — {@code double} round-trips that too.
 * These values are chosen to fail if any step ever parses a coordinate into a binary float.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoothLayoutRoundTripIntegrationTest {

    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothLayoutQueryService queries;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void awkwardNumbersSurviveTheRoundTrip() {
        Owner owner = newOwner("왕복");

        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"a","type":"DECORATION",
                   "position":{"x":2.123456789,"y":0.0,"z":-0.0},"rotationY":359.9},
                  {"objectId":"b","type":"DECORATION",
                   "position":{"x":-2.599999999,"y":0.999,"z":0.000000001},"rotationY":0.1}]}
                """);

        var draft = queries.findDraft(owner.boothId(), owner.userId()).orElseThrow();
        var first = draft.objects().get(0);
        var second = draft.objects().get(1);

        assertExactly("2.123456789", first.position().x());
        assertExactly("359.9", first.rotationY());
        assertExactly("-2.599999999", second.position().x());
        assertExactly("0.999", second.position().y());
        assertExactly("0.000000001", second.position().z());
        assertExactly("0.1", second.rotationY());
    }

    @Test
    void publishingCarriesTheSameNumbers() {
        Owner owner = newOwner("공개왕복");
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"a","type":"DECORATION",
                   "position":{"x":1.100000000000001,"y":0,"z":0},"rotationY":270}]}
                """);
        BoothLayoutTestSupport.grantLease(jdbc, owner.boothId(), owner.userId());

        layouts.publish(owner.boothId(), owner.userId());

        var view = queries.findPublished(owner.boothId());
        assertExactly("1.100000000000001", view.objects().get(0).position().x());
    }

    /**
     * An unknown field is refused, not dropped.
     *
     * <p>Dropping it would let the editor believe it saved something the server discarded — the
     * shape of T-24, where a silent fallback made a broken save look successful.
     */
    @Test
    void anUnknownFieldIsRefusedRatherThanDropped() {
        Owner owner = newOwner("미지필드");

        LayoutValidationFailedException failure = assertThrows(LayoutValidationFailedException.class,
                () -> layouts.saveDraft(owner.boothId(), owner.userId(), """
                        {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                          {"objectId":"a","type":"DECORATION","scale":2.0,
                           "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                        """));

        assertTrue(failure.errors().stream().anyMatch(error -> "MALFORMED_LAYOUT".equals(error.rule())),
                "알 수 없는 필드는 MALFORMED_LAYOUT로 거부되어야 합니다: " + failure.errors());
    }

    /**
     * The stored text carries the numbers the editor typed, not a re-rendered version of them.
     *
     * <p>Reads the column as text rather than through the entity: going back through the parser
     * would hide a change that both sides happen to parse the same way.
     */
    @Test
    void theStoredTextKeepsTheNumbersAsSent() {
        Owner owner = newOwner("원문보존");

        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"a","type":"DECORATION",
                   "position":{"x":2.10,"y":0.000,"z":-1.500},"rotationY":45.0}]}
                """);

        String stored = jdbc.queryForObject(
                "SELECT layout_json::text FROM booth_layout_drafts WHERE booth_id = ?",
                String.class, owner.boothId());

        // Trailing zeros are part of what the client sent; numeric keeps scale, so they survive.
        assertTrue(stored.contains("2.10"), "보낸 자릿수가 그대로여야 합니다: " + stored);
        assertTrue(stored.contains("0.000"), "보낸 자릿수가 그대로여야 합니다: " + stored);
        assertTrue(stored.contains("-1.500"), "보낸 자릿수가 그대로여야 합니다: " + stored);
        assertTrue(stored.contains("45.0"), "보낸 자릿수가 그대로여야 합니다: " + stored);
    }

    /**
     * The two spellings the database does change, pinned so they are known rather than discovered.
     *
     * <p>PostgreSQL {@code numeric} has no signed zero and writes exponents in full, so {@code -0.0}
     * is stored as {@code 0.0} and {@code 1e2} as {@code 100}. Both are the same <b>value</b> — the
     * same point in space — and nothing downstream can tell the difference; only the text differs.
     */
    @Test
    void negativeZeroAndExponentsAreRewrittenToTheSameValue() {
        Owner owner = newOwner("영과지수");

        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"a","type":"DECORATION",
                   "position":{"x":-0.0,"y":1e0,"z":0},"rotationY":0}]}
                """);

        var object = queries.findDraft(owner.boothId(), owner.userId()).orElseThrow().objects().get(0);

        assertEquals(0, object.position().x().signum(), "-0.0과 0.0은 같은 지점입니다.");
        assertExactly("1", object.position().y());
    }

    @Test
    void theBoothEdgeIsInsideTheBooth() {
        Owner owner = newOwner("경계");

        // 6m × 6m × 2.72m, origin at the floor centre. DECORATION의 실물이 벽 세 면에 정확히
        // 닿는 배치: x 2.7+0.3=3.0, z −2.7−0.3=−3.0, y 1.11+1.61=2.72. 경계는 안이다 (#19 ③).
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                  {"objectId":"corner","type":"DECORATION",
                   "position":{"x":2.7,"y":1.11,"z":-2.7},"rotationY":0}]}
                """);

        assertEquals(1, queries.findDraft(owner.boothId(), owner.userId()).orElseThrow().objects().size());
    }

    @Test
    void justOutsideTheBoothIsRefused() {
        Owner owner = newOwner("경계밖");

        assertThrows(LayoutValidationFailedException.class,
                () -> layouts.saveDraft(owner.boothId(), owner.userId(), """
                        {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[
                          {"objectId":"a","type":"DECORATION",
                           "position":{"x":3.001,"y":0,"z":0},"rotationY":0}]}
                        """));
    }

    @Test
    void anEmptyLayoutIsValid() {
        Owner owner = newOwner("빈배치");

        layouts.saveDraft(owner.boothId(), owner.userId(),
                """
                {"expectedRevision":0,"schemaVersion":1,"template":"PROJECT_EXHIBITION","objects":[]}
                """);

        assertEquals(0, queries.findDraft(owner.boothId(), owner.userId()).orElseThrow().objects().size());
    }

    private void assertExactly(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "값이 그대로 돌아와야 합니다. 기대=" + expected + " 실제=" + actual);
    }

    private Owner newOwner(String prefix) {
        Long userId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(userId, prefix + " 부스")).getId();
        return new Owner(userId, boothId);
    }

    private record Owner(Long userId, Long boothId) { }
}
