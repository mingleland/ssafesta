package com.example.ssafesta.common;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Which database constraint did this write break?
 *
 * <p>Answering that is the difference between "someone took the slot" and "you already hold one" —
 * two 409s with different fixes for the user. Every service that translates a
 * {@code DataIntegrityViolationException} needs the same three lines, so they live here rather than
 * being copied a third time (spec 004 {@code BoothLeaseService}, spec 009 {@code ProjectService}).
 */
public final class ConstraintViolations {

    /**
     * A wrapped exception chain is normally two or three deep. The bound is not a real limit — it is
     * a guarantee that a malformed chain cannot hang the request thread.
     */
    private static final int MAX_DEPTH = 32;

    private ConstraintViolations() {
    }

    /**
     * @return the database's name for the violated constraint, or {@code null} when the driver
     *         omits it or nothing in the chain is a constraint violation. <b>Callers must handle
     *         {@code null} by not translating</b> — guessing turns an unexplained failure into a
     *         confidently wrong answer.
     */
    public static String nameOf(Throwable throwable) {
        Throwable cause = throwable;
        // A self-referencing cause is the common malformed chain, but two exceptions pointing at
        // each other would slip past that check and spin forever. Bounding the walk covers both.
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return null;
    }

    /** {@code true} when the violated constraint is the named index, whatever case the driver uses. */
    public static boolean isViolationOf(Throwable throwable, String constraintName) {
        String actual = nameOf(throwable);
        return actual != null && actual.toLowerCase().contains(constraintName.toLowerCase());
    }
}
