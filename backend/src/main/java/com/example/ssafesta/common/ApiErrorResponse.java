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
 *
 * <p>{@code balance} is the one exception to "always present", and it is absent rather than null
 * on every error that is not about coins. It exists because a refusal a user can act on has to say
 * what they have: "코인이 부족합니다" without a number leaves them guessing how short they are, and
 * the client's own cached balance is stale by definition when this fires.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResponse(String code, String message, String requestId,
                               List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer balance) {

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId) {
        return of(code, message, requestId, List.of(), List.of());
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId,
                                      List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings) {
        return of(code, message, requestId, errors, warnings, null);
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String requestId,
                                      List<ApiErrorDetail> errors, List<ApiErrorDetail> warnings,
                                      Integer balance) {
        return new ApiErrorResponse(code.name(), message, requestId,
                copyOrEmpty(errors), copyOrEmpty(warnings), balance);
    }

    private static List<ApiErrorDetail> copyOrEmpty(List<ApiErrorDetail> details) {
        return details == null ? List.of() : List.copyOf(details);
    }
}
