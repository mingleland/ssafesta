package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The layout was refused (spec 005 FR-007).
 *
 * <p>Carries both lists: the warnings ride along even though they did not cause the refusal, so the
 * editor can show everything wrong at once instead of one problem per attempt.
 */
public class LayoutValidationFailedException extends ApiException {

    public LayoutValidationFailedException(String message, LayoutValidationResult result) {
        super(ErrorCode.LAYOUT_VALIDATION_FAILED, message, result.errors(), result.warnings());
    }
}
