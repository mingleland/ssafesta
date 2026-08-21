package com.example.ssafesta.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The single error shape of this API (docs/08 §1.3).
 *
 * <p><b>{@code errors} and {@code warnings} are always present</b>, empty when there is nothing to
 * report. Omitting them when empty saved a few bytes and cost the client a crash: {@code
 * errors.length} throws on an absent key but reads {@code 0} on an empty array. A field that is
 * sometimes there is harder to consume than one that is always there.
 *
 * <p>{@code requestId} is the same value as the {@code X-Request-Id} response header and the
 * {@code requestId} in the server log line for that request ({@link RequestIdFilter}) — a user can
 * quote it and the log is findable.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResponse(String code, String message, String requestId,
                               List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId) {
        return of(code, message, requestId, List.of(), List.of());
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId,
                                      List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {
        return new ApiErrorResponse(code.name(), message, requestId,
                copyOrEmpty(errors), copyOrEmpty(warnings));
    }

    private static List<ApiErrorDetail> copyOrEmpty(List<ApiErrorDetail> details) {
        return details == null ? List.of() : List.copyOf(details);
    }
}
