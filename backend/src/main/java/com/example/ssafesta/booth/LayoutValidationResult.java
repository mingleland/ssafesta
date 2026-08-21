package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import java.util.ArrayList;
import java.util.List;

/**
 * What a layout check found, split into what blocks and what does not (spec 005 FR-016).
 *
 * <p>The split exists because C-04 — whether an unlinked object may be published — is still 기획's
 * to decide. With two lists, deciding it moves one rule from {@code warnings} to {@code errors} and
 * changes nothing else: not the response shape, not the frontend parser (research R-05).
 */
public final class LayoutValidationResult {

    private final List<ApiErrorDetail> errors = new ArrayList<>();
    private final List<ApiErrorDetail> warnings = new ArrayList<>();

    void addError(String rule, String message) {
        errors.add(ApiErrorDetail.of(rule, message));
    }

    void addError(String rule, String objectId, String message) {
        errors.add(ApiErrorDetail.of(rule, objectId, message));
    }

    void addWarning(String rule, String objectId, String message) {
        warnings.add(ApiErrorDetail.of(rule, objectId, message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<ApiErrorDetail> errors() {
        return List.copyOf(errors);
    }

    public List<ApiErrorDetail> warnings() {
        return List.copyOf(warnings);
    }
}
