package com.example.ssafesta.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of a validation result (spec 005 FR-016).
 *
 * @param rule     machine-readable rule name the client can branch on (e.g. {@code OBJECT_LIMIT})
 * @param objectId the layout object at fault, or {@code null} when the rule is about the whole request
 * @param message  what the user should be told
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorDetail(String rule, String objectId, String message) {

    public static ApiErrorDetail of(String rule, String message) {
        return new ApiErrorDetail(rule, null, message);
    }

    public static ApiErrorDetail of(String rule, String objectId, String message) {
        return new ApiErrorDetail(rule, objectId, message);
    }
}
