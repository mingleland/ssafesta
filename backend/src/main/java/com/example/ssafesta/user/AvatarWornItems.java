package com.example.ssafesta.user;

import java.util.ArrayList;
import java.util.List;

/** Reads only the eight equipped-item identifiers needed for ownership checks. */
public final class AvatarWornItems {

    private static final String MODULAR_PREFIX = "fa|";
    private static final String ITEM_PREFIX = "i=";
    private static final int SLOT_COUNT = 8;

    private AvatarWornItems() {
    }

    /**
     * Returns normalized non-zero slot values, or an empty list for every format this server does
     * not recognize. That permissive fallback is deliberate: presets are not shop items, and an
     * encoder format change must not lock every member out of saving an avatar.
     */
    public static List<String> parse(String avatarCode) {
        if (avatarCode == null || !avatarCode.startsWith(MODULAR_PREFIX)) {
            return List.of();
        }
        for (String segment : avatarCode.split("\\|", -1)) {
            if (segment.startsWith(ITEM_PREFIX)) {
                return parseSlots(segment.substring(ITEM_PREFIX.length()));
            }
        }
        return List.of();
    }

    private static List<String> parseSlots(String encodedSlots) {
        String[] slots = encodedSlots.split(",", -1);
        if (slots.length != SLOT_COUNT) {
            return List.of();
        }
        List<String> equipped = new ArrayList<>(SLOT_COUNT);
        try {
            for (String slot : slots) {
                long value = Long.parseLong(slot);
                if (value < 0) {
                    return List.of();
                }
                if (value != 0) {
                    equipped.add(Long.toString(value));
                }
            }
        } catch (NumberFormatException exception) {
            return List.of();
        }
        return List.copyOf(equipped);
    }
}
