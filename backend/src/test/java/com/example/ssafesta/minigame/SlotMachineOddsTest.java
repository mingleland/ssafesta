package com.example.ssafesta.minigame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The odds table, checked as arithmetic rather than as text (spec 021 FR-005, GitLab #205 확정값 1).
 *
 * <p>The number that matters is <b>RTP 0.55</b>. It is the one property of this table anyone agreed
 * to: it is what makes the machine a coin sink rather than a coin source, and it is why #205 could
 * settle the "일일 한도" question by saying there is none. A yml edit that moves it is a change to
 * the coin economy, and it should have to come past this assertion to happen.
 */
class SlotMachineOddsTest {

    /** The shipped table (application.yml) — 낙첨 78 · ×2 15 · ×3 5 · ×5 2. */
    private static SlotMachineProperties shipped() {
        return new SlotMachineProperties(10, List.of("plaza-slot-01", "plaza-slot-02"), List.of(
                new SlotMachineProperties.Tier(2, new BigDecimal("0.15")),
                new SlotMachineProperties.Tier(3, new BigDecimal("0.05")),
                new SlotMachineProperties.Tier(5, new BigDecimal("0.02"))));
    }

    @Test
    void theShippedTableReturns55PercentOfWhatItTakes() {
        SlotMachineProperties config = shipped();
        BigDecimal rtp = BigDecimal.ZERO;
        for (SlotMachineProperties.Tier tier : config.tiers()) {
            rtp = rtp.add(tier.weight().multiply(BigDecimal.valueOf(tier.multiplier())));
        }
        assertEquals(0, rtp.compareTo(new BigDecimal("0.55")),
                "RTP 가 0.55 가 아닙니다 — 코인 경제가 바뀝니다: " + rtp);
    }

    @Test
    void everyDrawLandsOnADeclaredTier() {
        SlotMachineProperties config = shipped();
        int[] hits = new int[config.tiers().size() + 1];
        for (int i = 0; i < 200_000; i++) {
            int tier = config.rollTier();
            assertTrue(tier >= 0 && tier < hits.length, "등급이 표 밖입니다: " + tier);
            hits[tier]++;
        }
        // Wide bounds on purpose — this is not a randomness test. It catches the errors that
        // actually happen to a cumulative walk: an inverted comparison, an off-by-one tier index,
        // a table read in the wrong order. At 200k draws each band is ~50 sigma from these edges.
        assertBand("낙첨", hits[0], 0.76, 0.80);
        assertBand("x2", hits[1], 0.14, 0.16);
        assertBand("x3", hits[2], 0.045, 0.055);
        assertBand("x5", hits[3], 0.015, 0.025);
    }

    @Test
    void tierZeroPaysNothingAndEveryOtherTierPaysItsMultiplier() {
        SlotMachineProperties config = shipped();
        assertEquals(0, config.multiplierOfTier(0));
        assertEquals(2, config.multiplierOfTier(1));
        assertEquals(3, config.multiplierOfTier(2));
        assertEquals(5, config.multiplierOfTier(3));
        // Out of range pays nothing rather than throwing: the caller multiplies a bet by this.
        assertEquals(0, config.multiplierOfTier(4));
        assertEquals(0, config.multiplierOfTier(-1));
    }

    @Test
    void onlyTheMachinesInTheWhitelistAreKnown() {
        SlotMachineProperties config = shipped();
        assertTrue(config.knows("plaza-slot-01"));
        assertTrue(config.knows("plaza-slot-02"));
        assertTrue(!config.knows("plaza-slot-03"));
        assertTrue(!config.knows(null));
    }

    @Test
    void aFourthTierIsRefusedBecauseUnityCannotShowIt() {
        // HttpSlotMachineClient clamps tier to 0..3 and there are three win presets. A fourth band
        // would pay correctly and animate as the third — right ledger, wrong screen, no error.
        assertThrows(IllegalStateException.class, () -> new SlotMachineProperties(10, List.of("m"), List.of(
                new SlotMachineProperties.Tier(2, new BigDecimal("0.1")),
                new SlotMachineProperties.Tier(3, new BigDecimal("0.05")),
                new SlotMachineProperties.Tier(5, new BigDecimal("0.02")),
                new SlotMachineProperties.Tier(10, new BigDecimal("0.008")))));
    }

    @Test
    void aTableThatIsNotAscendingByMultiplierIsRefused() {
        // tier is what Unity picks the reel preset from, so a descending table would give x5 the
        // weakest animation.
        assertThrows(IllegalStateException.class, () -> new SlotMachineProperties(10, List.of("m"), List.of(
                new SlotMachineProperties.Tier(5, new BigDecimal("0.02")),
                new SlotMachineProperties.Tier(2, new BigDecimal("0.15")))));
    }

    @Test
    void weightsThatSumPastOneAreRefused() {
        assertThrows(IllegalStateException.class, () -> new SlotMachineProperties(10, List.of("m"), List.of(
                new SlotMachineProperties.Tier(2, new BigDecimal("0.7")),
                new SlotMachineProperties.Tier(3, new BigDecimal("0.4")))));
    }

    @Test
    void aWeightFinerThanTenThousandthsIsRefusedRatherThanRounded() {
        // Silently rounding 0.00005 to 0 would give a band that can never hit, and the table would
        // still look like it pays.
        assertThrows(IllegalStateException.class, () -> new SlotMachineProperties(10, List.of("m"),
                List.of(new SlotMachineProperties.Tier(2, new BigDecimal("0.00005")))));
    }

    @Test
    void anEmptyMachineWhitelistIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> new SlotMachineProperties(10, List.of(), List.of(
                        new SlotMachineProperties.Tier(2, new BigDecimal("0.15")))));
    }

    private static void assertBand(String name, int hits, double low, double high) {
        double share = hits / 200_000.0;
        assertTrue(share >= low && share <= high,
                name + " 비율이 " + low + "~" + high + " 밖입니다: " + share);
    }
}
