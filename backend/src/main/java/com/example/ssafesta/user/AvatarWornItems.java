package com.example.ssafesta.user;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the eight equipped-item identifiers an ownership check needs, and nothing else.
 *
 * <p><b>This mirrors {@code AvatarAppearance.Decode} slot for slot.</b> Whatever the server decides
 * a code claims has to be what Unity will actually draw from it — every place the two disagree is a
 * way to wear something you do not own, because the server checks one set and the client renders
 * another.
 */
public final class AvatarWornItems {

    private static final String MODULAR_PREFIX = "fa|";
    private static final String ITEM_PREFIX = "i=";
    private static final int SLOT_COUNT = 8;

    private AvatarWornItems() {
    }

    /**
     * Returns the non-zero slot values, or an empty list for a shape this server does not recognize.
     *
     * <p>The permissive fallback covers <b>unrecognized shapes</b> — presets are not shop items, and
     * an encoder format change must not lock every member out of saving an avatar. It does not cover
     * a recognized shape with bad content: a single unparsable slot used to discard the whole list,
     * which handed the other seven a free pass. Unity keeps those seven, so the server keeps them too.
     */
    public static List<String> parse(String avatarCode) {
        if (avatarCode == null || !avatarCode.startsWith(MODULAR_PREFIX)) {
            return List.of();
        }
        String[] chosen = chooseItemSegment(avatarCode.split("\\|", -1));
        if (chosen == null) {
            return List.of();
        }
        List<String> equipped = new ArrayList<>(SLOT_COUNT);
        for (String slot : chosen) {
            int value = slotValue(slot);
            if (value > 0) {
                equipped.add(Integer.toString(value));
            }
        }
        return List.copyOf(equipped);
    }

    /**
     * The <b>last</b> {@code i=} carrying exactly eight slots wins.
     *
     * <p>{@code Decode} walks every segment and assigns as it goes, so a second {@code i=} overwrites
     * the first — and its {@code values.Length == 8} guard means a malformed one leaves the previous
     * values standing. Taking the first match instead let {@code fa|i=<owned>|i=<unowned>} be checked
     * against the owned set and rendered from the unowned one.
     *
     * <p>Scanning from index 1 is also {@code Decode}'s behaviour: segment 0 is the format marker.
     */
    private static String[] chooseItemSegment(String[] segments) {
        String[] chosen = null;
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (!segment.startsWith(ITEM_PREFIX)) {
                continue;
            }
            String[] slots = segment.substring(ITEM_PREFIX.length()).split(",", -1);
            if (slots.length == SLOT_COUNT) {
                chosen = slots;
            }
        }
        return chosen;
    }

    /**
     * One slot, read the way {@code int.TryParse} reads it.
     *
     * <p>Failure means {@code 0} — Unity leaves the field at its default and draws nothing there —
     * so an unreadable slot claims nothing while its neighbours still count. {@code int} rather than
     * {@code long} because a value past {@code int} range fails in Unity too, and the surrounding
     * whitespace is trimmed because {@code NumberStyles.Integer} allows it; parsing more strictly
     * than the client does would turn a slot Unity renders into one the server never checks.
     *
     * <p>A negative parses fine and then resolves to no item ({@code AvatarCatalog.Get} finds none),
     * so it is dropped here alongside {@code 0}.
     */
    private static int slotValue(String slot) {
        try {
            return Integer.parseInt(slot.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
