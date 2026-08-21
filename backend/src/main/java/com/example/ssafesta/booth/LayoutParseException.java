package com.example.ssafesta.booth;

/**
 * The layout text could not be read at all.
 *
 * <p>Not an {@code ApiException}: unreadable JSON is one of the validation findings, and
 * {@link LayoutValidator} folds it into the {@code errors} list so the client gets the same shape
 * as every other rejection (spec 005 FR-016).
 */
class LayoutParseException extends RuntimeException {

    LayoutParseException(String message) {
        super(message);
    }
}
