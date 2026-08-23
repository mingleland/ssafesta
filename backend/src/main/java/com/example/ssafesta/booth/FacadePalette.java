package com.example.ssafesta.booth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The twelve colours a booth exterior may use (contracts/layout-api.md §6, #17 2026-08-23).
 *
 * <p><b>One palette for the whole world</b>, not one per {@code themeCode} — the two are
 * independent, so the four themes and these twelve colours are not a cross product.
 *
 * <p>The values here are the canonical ones (Tailwind 500). Figma follows this table, not the other
 * way round; if a colour changes it changes here first. {@code code} and {@code label} are the FE's
 * business — the server stores only the hex — but the codes are kept so the two sides can talk
 * about "the blue one" without pasting hex at each other, and so a future
 * {@code GET /booth-facade-options} has one place to read from.
 */
public final class FacadePalette {

    private static final Map<String, String> HEX_BY_CODE = table();
    private static final Set<String> HEXES = Set.copyOf(HEX_BY_CODE.values());

    private FacadePalette() { }

    /**
     * The stored spelling of a hex colour.
     *
     * <p>{@code #ef4444} and {@code #EF4444} are the same colour written two ways, so one of them
     * has to win or the same booth compares unequal to itself across a save. Uppercase wins
     * (contracts/layout-api.md §6). This is the documented exception to "BE does not alter values" —
     * it is a spelling change, not a value change.
     */
    static String normalize(String hex) {
        return hex == null ? null : hex.toUpperCase(Locale.ROOT);
    }

    /** Whether a hex colour — in either spelling — is one of the twelve. */
    static boolean contains(String hex) {
        return HEXES.contains(normalize(hex));
    }

    /** The table itself, in the order the contract lists it (the order FE paints the swatches). */
    static Map<String, String> hexByCode() {
        return HEX_BY_CODE;
    }

    private static Map<String, String> table() {
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("RED", "#EF4444");
        palette.put("ORANGE", "#F97316");
        palette.put("AMBER", "#F59E0B");
        palette.put("YELLOW", "#EAB308");
        palette.put("LIME", "#84CC16");
        palette.put("GREEN", "#22C55E");
        palette.put("TEAL", "#14B8A6");
        palette.put("CYAN", "#06B6D4");
        palette.put("BLUE", "#3B82F6");
        palette.put("INDIGO", "#6366F1");
        palette.put("PURPLE", "#A855F7");
        palette.put("PINK", "#EC4899");
        return Collections.unmodifiableMap(palette);
    }
}
