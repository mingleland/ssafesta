package com.example.ssafesta.minigame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.minigame.MinigameProperties.Tier;
import com.example.ssafesta.minigame.MinigameProperties.TimerStop;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The reward band lookup and the configuration it reads, with no Spring and no container.
 *
 * <p>The band lookup earns a test of its own because the obvious implementation is wrong. The table
 * is ordered widest-first so that appending a tighter band never renumbers the existing tiers; scan
 * it forwards and return on the first match and a 0.05s stop collects the 0.5s band's reward. That
 * mistake pays the wrong amount silently — nothing throws, nothing logs, the numbers are just low.
 */
class TimerStopJudgeTest {

    /** The shipped defaults from {@code application.yml}. */
    private static final TimerStop CONFIG = timerStop(
            tier("0.5", 2),
            tier("0.1", 5));

    @ParameterizedTest(name = "오차 {0}초 → tier {1}")
    @CsvSource({
            "0.501, 0",
            "0.500, 1",
            "0.300, 1",
            "0.101, 1",
            "0.100, 2",
            "0.050, 2",
            "0.000, 2"})
    void theNarrowestMatchingBandWins(String error, int expectedTier) {
        assertEquals(expectedTier, CONFIG.tierOf(new BigDecimal(error)));
    }

    @Test
    void bandBoundsAreInclusive() {
        // 0.100 is *in* the 0.1s band, not just outside it. A client that lands exactly on the
        // published bound and is told it missed has been lied to.
        assertEquals(2, CONFIG.tierOf(new BigDecimal("0.100")));
        assertEquals(1, CONFIG.tierOf(new BigDecimal("0.500")));
    }

    @Test
    void trailingZerosDoNotChangeTheBand() {
        // compareTo, not equals: new BigDecimal("0.1").equals(new BigDecimal("0.100")) is false.
        assertEquals(2, CONFIG.tierOf(new BigDecimal("0.1000")));
        assertEquals(2, CONFIG.tierOf(new BigDecimal("0.10")));
    }

    @Test
    void tierZeroPaysNothingAndUnknownTiersDoTheSame() {
        assertEquals(0, CONFIG.coinsOfTier(0));
        assertEquals(2, CONFIG.coinsOfTier(1));
        assertEquals(5, CONFIG.coinsOfTier(2));
        assertEquals(0, CONFIG.coinsOfTier(3));
        assertEquals(0, CONFIG.coinsOfTier(-1));
    }

    @Test
    void failAfterIsTheTargetPlusTheMargin() {
        assertEquals(0, new BigDecimal("10.381")
                .compareTo(CONFIG.failAfterSeconds(new BigDecimal("7.381"))));
    }

    // ── 잘못된 표는 부팅을 막는다 ────────────────────────────────────────────

    @Test
    void bandsListedNarrowestFirstAreRefused() {
        // This is the table that would silently pay 2 coin for a perfect stop. Refusing to start is the
        // cheap way to find out; the alternative is noticing a week later from the ledger.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> timerStop(tier("0.1", 5), tier("0.5", 2)));
        assertTrue(failure.getMessage().contains("내림차순"), failure.getMessage());
    }

    @Test
    void aNarrowerBandThatPaysLessIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> timerStop(tier("0.5", 5), tier("0.1", 2)));
        assertTrue(failure.getMessage().contains("오름차순"), failure.getMessage());
    }

    @Test
    void negativeOrZeroRewardsAreRefused() {
        // Ascending order alone accepts -5, -1. tier 0 is already the no-reward case, so a reward
        // band that pays nothing has no meaning.
        assertThrows(IllegalStateException.class, () -> tier("0.5", -5));
        assertThrows(IllegalStateException.class, () -> tier("0.5", 0));
    }

    @Test
    void anEmptyTableIsRefused() {
        assertThrows(IllegalStateException.class, () -> timerStop());
    }

    @Test
    void aDailyCapOfZeroIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> new MinigameProperties(0, timerStop(tier("0.5", 2))));
    }

    @Test
    void anInvertedTargetRangeIsRefused() {
        assertThrows(IllegalStateException.class, () -> new TimerStop(new BigDecimal("10.0"),
                new BigDecimal("5.0"), BigDecimal.ONE, BigDecimal.ONE, List.of(tier("0.5", 2))));
    }

    @Test
    void aTargetFinerThanOneMillisecondIsRefused() {
        // target_value is NUMERIC(8,3) and the draw uses longValueExact, so a four-decimal bound
        // would blow up at the first session rather than at boot.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new TimerStop(new BigDecimal("5.0001"), new BigDecimal("10.0"),
                        BigDecimal.ONE, BigDecimal.ONE, List.of(tier("0.5", 2))));
        assertTrue(failure.getMessage().contains("밀리초"), failure.getMessage());
    }

    @Test
    void aNonPositiveToleranceIsRefused() {
        assertThrows(IllegalStateException.class, () -> new TimerStop(new BigDecimal("5.0"),
                new BigDecimal("10.0"), BigDecimal.ONE, BigDecimal.ZERO, List.of(tier("0.5", 2))));
    }

    private static TimerStop timerStop(Tier... tiers) {
        return new TimerStop(new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("3.0"),
                new BigDecimal("2.0"), List.of(tiers));
    }

    private static Tier tier(String maxErrorSeconds, int coins) {
        return new Tier(new BigDecimal(maxErrorSeconds), coins);
    }
}
