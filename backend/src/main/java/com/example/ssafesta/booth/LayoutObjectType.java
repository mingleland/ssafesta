package com.example.ssafesta.booth;

import java.util.Optional;

/**
 * The object kinds a booth layout may contain (spec 005 §공통 계약, 3파트 canonical 문자열).
 *
 * <p>This list is a <b>server-side whitelist</b>, which is what keeps 헌법 4조 true: adding a kind
 * is a data change here, not a Unity rebuild. Unity separately ignores kinds it does not know
 * (SC-005), so the two sides can move at different speeds.
 *
 * <p>The POC-era spellings {@code SURVEY} and {@code CONSULT_DESK} are deliberately absent. Unity
 * still <i>reads</i> them for frozen v0.0.1 data, but nothing new may be stored under them —
 * otherwise the old names never die.
 */
public enum LayoutObjectType {

    AI_AGENT(true),
    VIDEO_SCREEN(true),
    PROJECT_PANEL(true),
    SURVEY_KIOSK(true),
    RECRUITMENT_BOARD(true),
    CONSULTATION_DESK(true),
    LAPTOP(true),
    LIKE_VOTE(true),
    FURNITURE(false),
    DECORATION(false);

    private final boolean requiresConfig;

    LayoutObjectType(boolean requiresConfig) {
        this.requiresConfig = requiresConfig;
    }

    /**
     * Whether the object is meant to point at a piece of content.
     *
     * <p>Drives the {@code CONFIG_NOT_LINKED} warning only — whether an unlinked object blocks
     * publishing is C-04 and still undecided (research R-05).
     */
    public boolean requiresConfig() {
        return requiresConfig;
    }

    public static Optional<LayoutObjectType> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (LayoutObjectType type : values()) {
            if (type.name().equals(raw)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
