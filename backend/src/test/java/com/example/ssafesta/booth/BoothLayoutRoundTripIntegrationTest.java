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
                {"expectedRevision":0,"schemaVersion":1,"template":"DEFAULT","objects":[
                  {"objectId":"a","type":"DECORATION",
                   "position":{"x":2.123456789,"y":0.0,"z":-0.0},"rotationY":359.9},
                  {"objectId":"b","type":"DECORATION",
                   "position":{"x":-9.999999999,"y":4.999,"z":0.000000001},"rotationY":0.1}]}
                """);

        var draft = queries.findDraft(owner.boothId(), owner.userId()).orElseThrow();
        var first = draft.objects().get(0);
        var second = draft.objects().get(1);

        assertExactly("2.123456789", first.position().x());
        assertExactly("359.9", first.rotationY());
        assertExactly("-9.999999999", second.position().x());
        assertExactly("4.999", second.position().y());
        assertExactly("0.000000001", second.position().z());
        assertExactly("0.1", second.rotationY());
    }

    @Test
    void publishingCarriesTheSameNumbers() {
        Owner owner = newOwner("공개왕복");
        layouts.saveDraft(owner.boothId(), owner.userId(), """
                {"expectedRevision":0,"schemaVersion":1,"template":"DEFAULT","objects":[
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
                        {"expectedRevision":0,"schemaVersion":1,"template":"DEFAULT","objects":[
                          {"objectId":"a","type":"DECORATION","scale":2.0,
                           "position":{"x":0,"y":0,"z":0},"rotationY":0}]}
                        """));

        assertTrue(failure.errors().stream().anyMatch(error -> "MALFORMED_LAYOUT".equals(error.rule())),
                "알 수 없는 필드는 MALFORMED_LAYOUT로 거부되어야 합니다: " + failure.errors());
    }

    @Test
    void anEmptyLayoutIsValid() {
        Owner owner = newOwner("빈배치");

        layouts.saveDraft(owner.boothId(), owner.userId(),
                """
                {"expectedRevision":0,"schemaVersion":1,"template":"DEFAULT","objects":[]}
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
