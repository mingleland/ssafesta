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

    @Test
    void colorSegmentGrowthCannotChangeItemParsing() {
        String colors = "123,".repeat(200) + "456";
        assertEquals(List.of("11", "22"), AvatarWornItems.parse(
                "fa|g=0|i=11,0,0,0,22,0,0,0|p=" + colors + "|q=" + colors + "|w=" + colors));
    }
}
