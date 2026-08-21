package com.example.ssafesta.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The daily grant's "day" is a KST calendar date (spec 003 FR-003, Edge Case). These are the
 * boundary cases that decide whether a member gets one grant or two.
 */
class DailyGrantKstDateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void lastInstantBeforeKstMidnightStillBelongsToTheSameDay() {
        Instant justBeforeMidnight = Instant.parse("2026-08-19T14:59:59Z");

        assertEquals(LocalDate.of(2026, 8, 19), LocalDate.ofInstant(justBeforeMidnight, KST));
    }

    @Test
    void kstMidnightStartsTheNextDay() {
        Instant kstMidnight = Instant.parse("2026-08-19T15:00:00Z");

        assertEquals(LocalDate.of(2026, 8, 20), LocalDate.ofInstant(kstMidnight, KST));
    }

    @Test
    void utcWouldPutTheSameInstantOnTheWrongDay() {
        // Guards the policy choice itself: computing the key in UTC would let a member collect a
        // second grant between KST midnight and UTC midnight (SC-004).
        Instant kstMidnight = Instant.parse("2026-08-19T15:00:00Z");

        assertNotEquals(LocalDate.ofInstant(kstMidnight, UTC), LocalDate.ofInstant(kstMidnight, KST));
    }

    @Test
    void grantKeyCarriesTheMemberAndTheKstDate() {
        String key = DailyCoinGrantService.dailyGrantKey(42L, LocalDate.of(2026, 8, 19));

        assertEquals("DAILY_GRANT:42:2026-08-19", key);
    }

    @Test
    void grantKeysOfAdjacentDaysDiffer() {
        String today = DailyCoinGrantService.dailyGrantKey(42L, LocalDate.of(2026, 8, 19));
        String tomorrow = DailyCoinGrantService.dailyGrantKey(42L, LocalDate.of(2026, 8, 20));

        assertNotEquals(today, tomorrow);
    }

    @Test
    void grantPolicyRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new WalletProperties(-1, 50, KST));
        assertThrows(IllegalArgumentException.class, () -> new WalletProperties(200, -1, KST));
        assertThrows(IllegalArgumentException.class, () -> new WalletProperties(200, 50, null));
    }
}
