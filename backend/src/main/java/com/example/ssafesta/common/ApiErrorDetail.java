package com.example.ssafesta.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of a validation result (spec 005 FR-016, docs/08 §1.3-1).
 *
 * <p>{@code objectId} and {@code field} both point at "what is at fault" but at different things —
 * a placed layout object and a request field path — so they stay apart. Either one absent means the
 * key is absent, not {@code null}: a client reading {@code detail.field} on a rule that has none
 * should get {@code undefined}, not a null it has to special-case.
 *
 * @param rule     machine-readable rule name the client can branch on (e.g. {@code OBJECT_LIMIT})
 * @param objectId the layout object at fault, or {@code null} when the rule is about the whole request
 * @param field    the request field at fault, or {@code null} when the rule is not about one field
 * @param message  what the user should be told
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorDetail(String rule, String objectId, String field, String message) {

    /** The one global rule: a request field violated its constraint (docs/08 §1.3-1). */
    public static final String FIELD_INVALID = "FIELD_INVALID";

    public static ApiErrorDetail of(String rule, String message) {
        return new ApiErrorDetail(rule, null, null, message);
    }

    public static ApiErrorDetail of(String rule, String objectId, String message) {
        return new ApiErrorDetail(rule, objectId, null, message);
    }

    /**
     * A field-level rejection, with the field name in {@code field} and never in {@code rule}.
     *
     * <p>{@code rule} is fixed to {@link #FIELD_INVALID} on purpose. A field name in the rule slot
     * makes the rule vocabulary open-ended — every new DTO field would silently become a new rule
     * value — and a client that branches on a whitelist of rules would fall through on all of them
     * (#58 §3).
     */
    public static ApiErrorDetail field(String field, String message) {
        return new ApiErrorDetail(FIELD_INVALID, null, field, message);
    }
}
