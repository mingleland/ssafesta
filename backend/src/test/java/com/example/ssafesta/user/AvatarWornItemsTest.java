package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Locks the one server-readable part of the otherwise opaque avatar encoding. */
class AvatarWornItemsTest {

    @Test
    void parsesEightItemSlotsAndDropsUnequippedZeroes() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), AvatarWornItems.parse(
                "fa|g=0|i=1,2,3,0,4,5,0,6|p=1,2,3|q=4,5|w=6"));
    }

    @Test
    void returnsTheHatFamilyIdStoredInTheHatSlot() {
        assertEquals(List.of("1002"), AvatarWornItems.parse(
                "fa|g=1|i=0,0,1002,0,0,0,0,0|p=unchanged"));
    }

    @Test
    void ignoresPresetLegacyAndUnrecognizedShapes() {
        assertEquals(List.of(), AvatarWornItems.parse("sk_01"));
        assertEquals(List.of(), AvatarWornItems.parse("rt|3=SK_Hair"));
        assertEquals(List.of(), AvatarWornItems.parse("fa|g=0|p=1,2,3"));
        assertEquals(List.of(), AvatarWornItems.parse("fa|g=0|i=1,2,3"));
    }

    /**
     * One unreadable slot must not excuse the other seven.
     *
     * <p>Unity's {@code int.TryParse} leaves that field at zero and draws the rest, so discarding the
     * whole list here meant {@code i=1,…,7,abc} was rendered with seven items nobody had checked —
     * a full bypass bought with one junk character.
     */
    @Test
    void oneUnreadableSlotDoesNotExcuseTheOthers() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), AvatarWornItems.parse(
                "fa|g=0|i=1,2,3,4,5,6,7,abc|p=1"));
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), AvatarWornItems.parse(
                "fa|g=0|i=1,2,3,4,5,6,7,-1|p=1"));
        // Past int range: Unity's TryParse fails too, so that slot claims nothing and the rest stand.
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), AvatarWornItems.parse(
                "fa|g=0|i=1,2,3,4,5,6,7,99999999999|p=1"));
    }

    /** {@code NumberStyles.Integer} allows surrounding whitespace, so a padded slot still counts. */
    @Test
    void aPaddedSlotIsStillAClaim() {
        assertEquals(List.of("5"), AvatarWornItems.parse("fa|g=0|i= 5 ,0,0,0,0,0,0,0|p=1"));
    }

    /**
     * A second {@code i=} overwrites the first, exactly as {@code Decode} does.
     *
     * <p>Reading the first one let a code declare an owned set for the server and carry an unowned
     * set for the client in the same string.
     */
    @Test
    void theLastEightSlotItemSegmentWins() {
        assertEquals(List.of("9"), AvatarWornItems.parse(
                "fa|g=0|i=1,0,0,0,0,0,0,0|i=9,0,0,0,0,0,0,0|p=1"));
    }

    /**
     * A malformed later {@code i=} leaves the earlier one standing.
     *
     * <p>{@code Decode} only assigns inside {@code values.Length == 8}, so a short trailing segment
     * changes nothing there — and must change nothing here, or the earlier claims go unchecked.
     */
    @Test
    void aShortLaterItemSegmentDoesNotClearTheEarlierOne() {
        assertEquals(List.of("1"), AvatarWornItems.parse(
                "fa|g=0|i=1,0,0,0,0,0,0,0|i=9,9|p=1"));
    }

    @Test
    void colorSegmentGrowthCannotChangeItemParsing() {
        String colors = "123,".repeat(200) + "456";
        assertEquals(List.of("11", "22"), AvatarWornItems.parse(
                "fa|g=0|i=11,0,0,0,22,0,0,0|p=" + colors + "|q=" + colors + "|w=" + colors));
    }
}
