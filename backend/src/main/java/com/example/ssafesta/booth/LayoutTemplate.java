package com.example.ssafesta.booth;

import java.util.Optional;

/**
 * Booth layout templates (spec 005 FR-002).
 *
 * <p>C-06 — how many templates there are and what each one places — is undecided and belongs to
 * 기획. Until then the server only checks that the value is one it knows, and the template's actual
 * meaning lives in the editor. Deciding it later adds constants here and nothing else.
 */
public enum LayoutTemplate {

    DEFAULT,
    PROJECT_EXHIBITION;

    public static Optional<LayoutTemplate> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (LayoutTemplate template : values()) {
            if (template.name().equals(raw)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }
}
