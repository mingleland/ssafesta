package com.example.ssafesta.common;

import java.util.List;

/**
 * An error the client is meant to see, carrying the {@link ErrorCode} it should branch on.
 *
 * <p>Throw this instead of {@code ResponseStatusException}: the status alone does not tell a client
 * <i>which</i> conflict happened, and four different 409s already exist in spec 004 alone.
 *
 * <p>Domain exceptions may extend this when their meaning is fixed (an invalid nickname is always
 * {@code NICKNAME_INVALID}). When the same domain failure means different things to different
 * callers, leave the exception plain and let the caller translate it.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient List<ApiErrorDetail> errors;
    private final transient List<ApiErrorDetail> warnings;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public ApiException(ErrorCode errorCode, String message,
                        List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {
        super(message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * One request field violated its constraint — the shape eight throw sites were assembling by
     * hand.
     *
     * <p>The envelope is fixed: {@code 400 VALIDATION_FAILED}, one detail whose {@code rule} is
     * {@code FIELD_INVALID} and whose {@code field} names the offender. Spelling that out per call
     * site is how one of them eventually puts the field name in {@code rule} and breaks a client's
     * whitelist branch (#58 §3) — the very thing T058 was opened to fix.
     *
     * <p>The top-level {@code message} repeats the detail's on purpose: it is what the user is
     * shown, and a field-level rejection has nothing more general to say.
     */
    public static ApiException fieldInvalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field(field, message)), null);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiErrorDetail> errors() {
        return errors;
    }

    public List<ApiErrorDetail> warnings() {
        return warnings;
    }
}
