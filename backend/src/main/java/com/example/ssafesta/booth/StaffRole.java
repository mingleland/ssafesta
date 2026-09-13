package com.example.ssafesta.booth;

import java.util.Optional;

/**
 * What a booth staff row is allowed to do (spec 011 FR-002, C-09).
 *
 * <p>The vocabulary belongs to spec 011, but the enum lives here because it is the meaning of
 * {@link BoothStaff}'s {@code role} column and {@link BoothAccessGuard} — the only reader today —
 * is here too. 011's own package imports it rather than the other way round.
 *
 * <p><b>{@code CONSULTANT} is why this exists.</b> {@code requireEditor} used to pass anyone with a
 * row, which was harmless only because nothing created rows. The moment 011 opens invitations, a
 * consultant would inherit booth studio, AI agent, document, survey and project editing along with
 * the seat they were invited to — nine call sites, one gate.
 */
public enum StaffRole {

    /** Runs the booth on the owner's behalf: content plus staff management (spec 011 US3). */
    ADMIN(true),

    /** Edits what the booth shows. No staff management, no lease (D10). */
    CONTENT_EDITOR(true),

    /** Answers visitors. Explicitly <b>not</b> an editor — C-09 names this one. */
    CONSULTANT(false);

    private final boolean mayEditBoothContent;

    StaffRole(boolean mayEditBoothContent) {
        this.mayEditBoothContent = mayEditBoothContent;
    }

    /** Booth Studio layout and facade, and every other path behind {@code requireEditor}. */
    public boolean mayEditBoothContent() {
        return mayEditBoothContent;
    }

    /**
     * Unknown values read as {@link Optional#empty()}, not an exception.
     *
     * <p>V31 constrains the column, so a value outside the three means either a row older than that
     * migration or a role a future build added. Both must read as <b>no editing rights</b> — a 500
     * on an unknown role would turn a vocabulary drift into an outage, and defaulting to "allowed"
     * is the failure this whole enum exists to prevent.
     */
    public static Optional<StaffRole> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (StaffRole role : values()) {
            if (role.name().equalsIgnoreCase(raw)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
