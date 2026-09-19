package com.example.ssafesta.minigame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The odds table, checked as arithmetic rather than as text (spec 021 FR-005, GitLab #205 확정값 1).
 *
 * <p>The number that matters is <b>RTP 0.68</b> (S15P21A604-921; it was 0.52 when the table was
 * first agreed in #205 확정값 1). It is the one property of this table anyone agreed to: it is what
 * makes the machine a coin sink rather than a coin source, and it is why #205 could settle the
 * "일일 한도" question by saying there is none. A yml edit that moves it is a change to the coin
 * economy, and it should have to come past this assertion to happen.
 *
 * <p>Which is why the table is <b>read from {@code application.yml}</b> rather than retyped here.
 * A copy would let the two drift, and the drifting copy is the one that keeps passing.
 *
 * <p>It binds the <b>default</b> document only, not the profile overlays. That is the whole table
 * today — no profile sets {@code app.minigame.slot-machine} — but a deployment that started
 * overriding the odds per profile would need this to bind that profile too, or the guard would
 * pass while production paid something else.
 */
class SlotMachineOddsTest {

    /** The shipped table, bound from {@code application.yml} — 낙첨 71.8 · ×2 25 · ×3 2 · ×10 1.2. */
    private static SlotMachineProperties shipped() {
        try {
            return new Binder(ConfigurationPropertySources.from(new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"))))
                    .bind("app.minigame.slot-machine", SlotMachineProperties.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException("application.yml 을 읽지 못했습니다", e);
        }
    }

    @Test
    void theShippedTableReturns68PercentOfWhatItTakes() {
        SlotMachineProperties config = shipped();
        BigDecimal rtp = BigDecimal.ZERO;
        for (SlotMachineProperties.Tier tier : config.tiers()) {
            rtp = rtp.add(tier.weight().multiply(BigDecimal.valueOf(tier.multiplier())));
        }
        assertEquals(0, rtp.compareTo(new BigDecimal("0.68")),
                "RTP 가 0.68 이 아닙니다 — 코인 경제가 바뀝니다: " + rtp);
        assertTrue(rtp.compareTo(BigDecimal.ONE) < 0,
                "RTP 는 1.0 미만이어야 합니다 — 슬롯이 코인 공급원이 됩니다: " + rtp);
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
        // a table read in the wrong order. At 200k draws the tightest band (x2) sits ~10 sigma from
        // these edges, so a passing run is not luck.
        assertBand("낙첨", hits[0], 0.707, 0.729);
        assertBand("x2", hits[1], 0.24, 0.26);
        assertBand("x3", hits[2], 0.016, 0.024);
        assertBand("x10", hits[3], 0.008, 0.016);
    }

    @Test
    void tierZeroPaysNothingAndEveryOtherTierPaysItsMultiplier() {
        SlotMachineProperties config = shipped();
        assertEquals(0, config.multiplierOfTier(0));
        assertEquals(2, config.multiplierOfTier(1));
        assertEquals(3, config.multiplierOfTier(2));
        assertEquals(10, config.multiplierOfTier(3));
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
        // tier is what Unity picks the reel preset from, so a descending table would give x10 the
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

    @Test
    void tenConsecutiveLossesForceTheNextSpinToTierOne() {
        assertTrue(!SlotMachineService.mustForceTierOne(9));
        assertTrue(SlotMachineService.mustForceTierOne(10));
        assertTrue(SlotMachineService.mustForceTierOne(11));
    }

    private static void assertBand(String name, int hits, double low, double high) {
        double share = hits / 200_000.0;
        assertTrue(share >= low && share <= high,
                name + " 비율이 " + low + "~" + high + " 밖입니다: " + share);
    }
}
