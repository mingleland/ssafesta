package com.example.ssafesta.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The single error shape of this API (docs/08 §1.3).
 *
 * <p>{@code errors} and {@code warnings} appear only on validation responses; everywhere else they
 * are {@code null} and drop out of the JSON, so a plain failure stays a three-field object.
 *
 * <p>{@code requestId} is the same value as the {@code X-Request-Id} response header and the
 * {@code requestId} in the server log line for that request ({@link RequestIdFilter}) — a user can
 * quote it and the log is findable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String code, String message, String requestId,
                               List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ApiErrorResponse(code.name(), message, requestId, null, null);
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId,
                                      List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {
        return new ApiErrorResponse(code.name(), message, requestId,
                emptyToNull(errors), emptyToNull(warnings));
    }

    private static List<ApiErrorDetail> emptyToNull(List<ApiErrorDetail> details) {
        return details == null || details.isEmpty() ? null : List.copyOf(details);
    }
}
